package com.robot.solar.repository

import android.content.Context
import com.robot.solar.BuildConfig
import com.robot.solar.data.session.SessionManager
import com.robot.solar.database.AppDatabase
import com.robot.solar.entity.StructuredLogDraft
import com.robot.solar.entity.StructuredLogEntity
import com.robot.solar.network.http.ApiClient
import com.robot.solar.network.http.dto.LoginRequest
import com.robot.solar.network.mqtt.DeviceTopicIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class AuthRepository private constructor(
    private val session: SessionManager
) {
    suspend fun login(email: String, password: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val api = ApiClient.getService(session)
            val resp = api.login(LoginRequest(email.trim(), password))
            session.saveAuthTokens(
                accessToken = resp.accessToken,
                refreshToken = resp.refreshToken
            )
            ApiClient.markAuthenticated()
            session.userEmail = email.trim()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun logout() {
        session.clear()
        ApiClient.reset()
    }

    fun isLoggedIn(): Boolean = session.isLoggedIn()

    companion object {
        @Volatile
        private var INSTANCE: AuthRepository? = null

        fun getInstance(context: Context): AuthRepository {
            return INSTANCE ?: synchronized(this) {
                val session = SessionManager.getInstance(context)
                INSTANCE ?: AuthRepository(session).also { INSTANCE = it }
            }
        }
    }
}

class LogRepository private constructor(
    private val database: AppDatabase,
    private val session: SessionManager
) {
    fun observeLogsDesc() = database.logDao().observeAllDesc()
    fun observeRecentCommands(limit: Int = 4) = database.logDao().observeRecentCommands(limit)
    suspend fun clearAll() = database.logDao().deleteAll()

    suspend fun upsert(draft: StructuredLogDraft) {
        database.logDao().upsert(
            StructuredLogEntity(
                eventId = draft.eventId ?: UUID.randomUUID().toString(),
                timestampMillis = draft.timestampMillis,
                deviceId = draft.deviceId ?: session.deviceId,
                productType = draft.productType ?: session.productType,
                source = draft.source,
                category = draft.category,
                eventType = draft.eventType,
                severity = draft.severity,
                direction = draft.direction,
                topic = draft.topic,
                cmdId = draft.cmdId,
                missionId = draft.missionId,
                action = draft.action,
                result = draft.result,
                summary = draft.summary,
                detailJson = draft.detailJson,
                dedupeKey = draft.dedupeKey,
                repeatCount = 1
            )
        )
        if (writesSinceCleanup.incrementAndGet() >= CLEANUP_INTERVAL) {
            writesSinceCleanup.set(0)
            database.logDao().deleteOlderThan(
                System.currentTimeMillis() - RETENTION_DAYS * MILLIS_PER_DAY
            )
            database.logDao().trimToNewest(MAX_ROWS)
        }
    }

    private val writesSinceCleanup = AtomicInteger()

    companion object {
        @Volatile
        private var INSTANCE: LogRepository? = null

        fun getInstance(context: Context): LogRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LogRepository(
                    AppDatabase.getInstance(context),
                    SessionManager.getInstance(context)
                ).also { INSTANCE = it }
            }
        }

        private const val MAX_ROWS = 2_000
        private const val RETENTION_DAYS = 30L
        private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1_000L
        private const val CLEANUP_INTERVAL = 50
    }
}

class DeviceRepository private constructor(
    private val session: SessionManager
) {
    suspend fun fetchDevices() = withContext(Dispatchers.IO) {
        ApiClient.getService(session).listDevices()
    }

    fun selectDevice(deviceId: String, displayName: String, productType: String?) {
        session.deviceId = deviceId
        session.deviceDisplayName = displayName
        session.productType = productType?.takeIf { it.isNotBlank() } ?: inferProductType(deviceId)
    }

    fun currentDeviceId(): String? = session.deviceId
    fun currentDeviceName(): String? = session.deviceDisplayName
    fun currentProductType(): String? = session.productType ?: session.deviceId?.let(::inferProductType)
    fun hasDevice(): Boolean = session.hasSelectedDevice()

    fun currentMqttIdentity(): DeviceTopicIdentity {
        val selectedDeviceId = session.deviceId.orEmpty()
        val mqttDeviceId = selectedDeviceId.takeIf(::isHardwareDeviceId)
            ?: BuildConfig.MQTT_DEFAULT_DEVICE_ID
        val productType = session.productType?.takeIf { it.isNotBlank() }
            ?: inferProductType(mqttDeviceId)
        return DeviceTopicIdentity(productType = productType, deviceId = mqttDeviceId)
    }

    private fun inferProductType(deviceId: String): String {
        return deviceId.substringBefore("_", missingDelimiterValue = "")
            .takeIf { it in SUPPORTED_PRODUCT_TYPES }
            ?: BuildConfig.MQTT_DEFAULT_PRODUCT_TYPE
    }

    private fun isHardwareDeviceId(deviceId: String): Boolean {
        val prefix = deviceId.substringBefore("_", missingDelimiterValue = "")
        return prefix in SUPPORTED_PRODUCT_TYPES
    }

    companion object {
        private val SUPPORTED_PRODUCT_TYPES = setOf("crawler", "hanging", "installer")

        @Volatile
        private var INSTANCE: DeviceRepository? = null

        fun getInstance(context: Context): DeviceRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DeviceRepository(SessionManager.getInstance(context)).also { INSTANCE = it }
            }
        }
    }
}

class JobRepository private constructor(
    private val session: SessionManager
) {
    suspend fun fetchJobs(deviceId: String) = withContext(Dispatchers.IO) {
        ApiClient.getService(session).listJobs(deviceId)
    }

    companion object {
        @Volatile
        private var INSTANCE: JobRepository? = null

        fun getInstance(context: Context): JobRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: JobRepository(SessionManager.getInstance(context)).also { INSTANCE = it }
            }
        }
    }
}

class FirmwareRepository private constructor(
    private val session: SessionManager
) {
    suspend fun latest(deviceId: String) = withContext(Dispatchers.IO) {
        ApiClient.getService(session).latestFirmware(deviceId)
    }

    suspend fun upgrade(deviceId: String, targetVersion: String?) = withContext(Dispatchers.IO) {
        ApiClient.getService(session).triggerFirmwareUpgrade(
            com.robot.solar.network.http.dto.FirmwareUpgradeRequest(deviceId, targetVersion)
        )
    }

    companion object {
        @Volatile
        private var INSTANCE: FirmwareRepository? = null

        fun getInstance(context: Context): FirmwareRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FirmwareRepository(SessionManager.getInstance(context)).also { INSTANCE = it }
            }
        }
    }
}

class WifiRepository private constructor(
    private val session: SessionManager
) {
    suspend fun get(deviceId: String) = withContext(Dispatchers.IO) {
        ApiClient.getService(session).getWifi(deviceId)
    }

    suspend fun update(deviceId: String, ssid: String, password: String) = withContext(Dispatchers.IO) {
        ApiClient.getService(session).updateWifi(
            deviceId,
            com.robot.solar.network.http.dto.WifiConfigUpdate(ssid, password)
        )
    }

    companion object {
        @Volatile
        private var INSTANCE: WifiRepository? = null

        fun getInstance(context: Context): WifiRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WifiRepository(SessionManager.getInstance(context)).also { INSTANCE = it }
            }
        }
    }
}
