package com.robot.solar.network.http

import com.robot.solar.BuildConfig
import com.robot.solar.data.session.SessionManager
import com.robot.solar.network.http.dto.CurrentMapResponse
import com.robot.solar.network.http.dto.DeviceDto
import com.robot.solar.network.http.dto.FirmwareDto
import com.robot.solar.network.http.dto.FirmwareUpgradeRequest
import com.robot.solar.network.http.dto.FirmwareUpgradeResponse
import com.robot.solar.network.http.dto.JobDto
import com.robot.solar.network.http.dto.LoginRequest
import com.robot.solar.network.http.dto.LogoutResponse
import com.robot.solar.network.http.dto.MapMetadataDto
import com.robot.solar.network.http.dto.RefreshRequest
import com.robot.solar.network.http.dto.TokenResponse
import com.robot.solar.network.http.dto.WifiConfigDto
import com.robot.solar.network.http.dto.WifiConfigUpdate
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.Authenticator
import okhttp3.Response
import okhttp3.Route
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit

interface ApiService {

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): TokenResponse

    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): TokenResponse

    @POST("auth/logout")
    suspend fun logout(@Body body: RefreshRequest): LogoutResponse

    @GET("devices")
    suspend fun listDevices(): List<DeviceDto>

    @GET("jobs")
    suspend fun listJobs(@Query("device_id") deviceId: String): List<JobDto>

    @GET("firmware/latest")
    suspend fun latestFirmware(@Query("device_id") deviceId: String): FirmwareDto

    @POST("firmware/upgrade")
    suspend fun triggerFirmwareUpgrade(@Body body: FirmwareUpgradeRequest): FirmwareUpgradeResponse

    @GET("devices/{device_id}/wifi")
    suspend fun getWifi(@Path("device_id") deviceId: String): WifiConfigDto

    @PUT("devices/{device_id}/wifi")
    suspend fun updateWifi(
        @Path("device_id") deviceId: String,
        @Body body: WifiConfigUpdate
    ): WifiConfigDto

    @GET("devices/{product_type}/{device_id}/maps/current")
    suspend fun getCurrentMap(
        @Path("product_type") productType: String,
        @Path("device_id") deviceId: String
    ): CurrentMapResponse

    @GET("devices/{product_type}/{device_id}/maps/{map_id}/versions/{map_version}")
    suspend fun getMapMetadata(
        @Path("product_type") productType: String,
        @Path("device_id") deviceId: String,
        @Path("map_id") mapId: Long,
        @Path("map_version") mapVersion: Long
    ): MapMetadataDto

    @GET("devices/{product_type}/{device_id}/maps/{map_id}/versions/{map_version}/content")
    suspend fun getMapContent(
        @Path("product_type") productType: String,
        @Path("device_id") deviceId: String,
        @Path("map_id") mapId: Long,
        @Path("map_version") mapVersion: Long
    ): ResponseBody
}

private interface AuthRefreshService {

    @POST("auth/refresh")
    fun refreshSync(@Body body: RefreshRequest): Call<TokenResponse>
}

object ApiClient {

    private var service: ApiService? = null
    private val refreshLock = Any()
    private val authExpiredNotified = AtomicBoolean(false)

    @Volatile
    private var authExpiredHandler: (() -> Unit)? = null

    fun setAuthExpiredHandler(handler: (() -> Unit)?) {
        authExpiredHandler = handler
    }

    fun markAuthenticated() {
        authExpiredNotified.set(false)
    }

    fun getService(sessionManager: SessionManager): ApiService {
        return service ?: synchronized(this) {
            service ?: create(sessionManager).also { service = it }
        }
    }

    fun reset() {
        synchronized(this) { service = null }
    }

    private fun create(sessionManager: SessionManager): ApiService {
        val baseUrl = BuildConfig.API_BASE_URL.let {
            if (it.endsWith("/")) it else "$it/"
        }

        val authInterceptor = Interceptor { chain ->
            val token = sessionManager.accessToken
            val request = if (!token.isNullOrBlank()) {
                chain.request().newBuilder()
                    .header("Authorization", "Bearer $token")
                    .build()
            } else {
                chain.request()
            }
            chain.proceed(request)
        }

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        val refreshClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()

        val refreshService = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(refreshClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AuthRefreshService::class.java)

        val authenticator = Authenticator { _: Route?, response: Response ->
            if (responseCount(response) >= 2) {
                notifyAuthExpired(sessionManager)
                return@Authenticator null
            }

            val path = response.request.url.encodedPath
            if (path.endsWith("/auth/login") || path.endsWith("/auth/refresh")) {
                return@Authenticator null
            }

            synchronized(refreshLock) {
                val requestToken = response.request.header("Authorization")
                    ?.removePrefix("Bearer ")
                    ?.takeIf { it.isNotBlank() }

                val currentAccessToken = sessionManager.accessToken

                if (
                    !currentAccessToken.isNullOrBlank() &&
                    currentAccessToken != requestToken
                ) {
                    return@synchronized response.request.newBuilder()
                        .header("Authorization", "Bearer $currentAccessToken")
                        .build()
                }

                val refreshToken = sessionManager.refreshToken
                    ?.takeIf { it.isNotBlank() }

                if (refreshToken == null) {
                    notifyAuthExpired(sessionManager)
                    return@synchronized null
                }

                try {
                    val refreshResponse = refreshService
                        .refreshSync(RefreshRequest(refreshToken))
                        .execute()

                    if (!refreshResponse.isSuccessful) {
                        if (refreshResponse.code() in listOf(400, 401, 403)) {
                            notifyAuthExpired(sessionManager)
                        }
                        return@synchronized null
                    }

                    val tokenResponse = refreshResponse.body()
                        ?: return@synchronized null

                    if (
                        tokenResponse.accessToken.isBlank() ||
                        tokenResponse.refreshToken.isBlank()
                    ) {
                        return@synchronized null
                    }

                    sessionManager.saveAuthTokens(
                        accessToken = tokenResponse.accessToken,
                        refreshToken = tokenResponse.refreshToken
                    )
                    markAuthenticated()

                    response.request.newBuilder()
                        .header(
                            "Authorization",
                            "Bearer ${tokenResponse.accessToken}"
                        )
                        .build()
                } catch (_: Exception) {
                    null
                }
            }
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            .authenticator(authenticator)
            .addInterceptor(logging)
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }

    private fun notifyAuthExpired(sessionManager: SessionManager) {
        if (!authExpiredNotified.compareAndSet(false, true)) return

        sessionManager.clearAuth()
        authExpiredHandler?.invoke()
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
