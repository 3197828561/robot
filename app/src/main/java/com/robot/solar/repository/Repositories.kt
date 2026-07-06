package com.robot.solar.repository

import android.content.Context
import com.robot.solar.data.session.SessionManager
import com.robot.solar.database.AppDatabase
import com.robot.solar.entity.LogType
import com.robot.solar.entity.SolarLogEntity
import com.robot.solar.network.http.ApiClient
import com.robot.solar.network.http.dto.LoginRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AuthRepository private constructor(
    private val session: SessionManager
) {
    suspend fun login(email: String, password: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val api = ApiClient.getService(session)
            val resp = api.login(LoginRequest(email.trim(), password))
            session.accessToken = resp.accessToken
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
    private val database: AppDatabase
) {
    fun observeLogsDesc() = database.logDao().observeAllDesc()

    suspend fun insert(type: LogType, content: String) {
        database.logDao().insert(
            SolarLogEntity(
                timestampMillis = System.currentTimeMillis(),
                type = type,
                content = content
            )
        )
    }

    companion object {
        @Volatile
        private var INSTANCE: LogRepository? = null

        fun getInstance(context: Context): LogRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LogRepository(AppDatabase.getInstance(context)).also { INSTANCE = it }
            }
        }
    }
}

class DeviceRepository private constructor(
    private val session: SessionManager
) {
    suspend fun fetchDevices() = withContext(Dispatchers.IO) {
        ApiClient.getService(session).listDevices()
    }

    fun selectDevice(deviceId: String, displayName: String) {
        session.deviceId = deviceId
        session.deviceDisplayName = displayName
    }

    fun currentDeviceId(): String? = session.deviceId
    fun currentDeviceName(): String? = session.deviceDisplayName
    fun hasDevice(): Boolean = session.hasSelectedDevice()

    companion object {
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
