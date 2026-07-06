package com.robot.solar.network.http

import com.robot.solar.BuildConfig
import com.robot.solar.data.session.SessionManager
import com.robot.solar.network.http.dto.DeviceDto
import com.robot.solar.network.http.dto.FirmwareDto
import com.robot.solar.network.http.dto.FirmwareUpgradeRequest
import com.robot.solar.network.http.dto.FirmwareUpgradeResponse
import com.robot.solar.network.http.dto.JobDto
import com.robot.solar.network.http.dto.LoginRequest
import com.robot.solar.network.http.dto.TokenResponse
import com.robot.solar.network.http.dto.WifiConfigDto
import com.robot.solar.network.http.dto.WifiConfigUpdate
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface ApiService {

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): TokenResponse

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
}

object ApiClient {

    private var service: ApiService? = null

    fun getService(sessionManager: SessionManager): ApiService {
        return service ?: synchronized(this) {
            service ?: create(sessionManager).also { service = it }
        }
    }

    fun reset() {
        synchronized(this) { service = null }
    }

    private fun create(sessionManager: SessionManager): ApiService {
        val authInterceptor = Interceptor { chain ->
            val token = sessionManager.accessToken
            val request = if (!token.isNullOrBlank()) {
                chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $token")
                    .build()
            } else {
                chain.request()
            }
            chain.proceed(request)
        }

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .build()

        val baseUrl = BuildConfig.API_BASE_URL.let {
            if (it.endsWith("/")) it else "$it/"
        }

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
