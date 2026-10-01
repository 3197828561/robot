package com.robot.solar.data.session

import android.content.Context
import androidx.core.content.edit

/**
 * 登录 Token 与当前选中设备的会话持久化
 */
class SessionManager private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var accessToken: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit { putString(KEY_TOKEN, value) }

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)
        private set(value) = prefs.edit { putString(KEY_REFRESH_TOKEN, value) }

    fun saveAuthTokens(accessToken: String, refreshToken: String) {
        prefs.edit {
            putString(KEY_TOKEN, accessToken)
            putString(KEY_REFRESH_TOKEN, refreshToken)
        }
    }

    var deviceId: String?
        get() = prefs.getString(KEY_DEVICE_ID, null)
        set(value) = prefs.edit { putString(KEY_DEVICE_ID, value) }

    var deviceDisplayName: String?
        get() = prefs.getString(KEY_DEVICE_NAME, null)
        set(value) = prefs.edit { putString(KEY_DEVICE_NAME, value) }

    var productType: String?
        get() = prefs.getString(KEY_PRODUCT_TYPE, null)
        set(value) = prefs.edit { putString(KEY_PRODUCT_TYPE, value) }

    var deviceRole: String?
        get() = prefs.getString(KEY_DEVICE_ROLE, null)
        set(value) = prefs.edit { putString(KEY_DEVICE_ROLE, value) }

    var canControlDevice: Boolean
        get() = prefs.getBoolean(KEY_CAN_CONTROL, false)
        set(value) = prefs.edit { putBoolean(KEY_CAN_CONTROL, value) }

    var canConfigureDevice: Boolean
        get() = prefs.getBoolean(KEY_CAN_CONFIGURE, false)
        set(value) = prefs.edit { putBoolean(KEY_CAN_CONFIGURE, value) }

    var canUpgradeDevice: Boolean
        get() = prefs.getBoolean(KEY_CAN_UPGRADE, false)
        set(value) = prefs.edit { putBoolean(KEY_CAN_UPGRADE, value) }

    var userEmail: String?
        get() = prefs.getString(KEY_EMAIL, null)
        set(value) = prefs.edit { putString(KEY_EMAIL, value) }

    fun isLoggedIn(): Boolean = !accessToken.isNullOrBlank()

    fun hasSelectedDevice(): Boolean = !deviceId.isNullOrBlank()

    fun clearAuth() {
        prefs.edit {
            remove(KEY_TOKEN)
            remove(KEY_REFRESH_TOKEN)
            remove(KEY_EMAIL)
        }
    }

    fun clear() {
        prefs.edit { clear() }
    }

    companion object {
        private const val PREFS = "solar_session"
        private const val KEY_TOKEN = "token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_PRODUCT_TYPE = "product_type"
        private const val KEY_DEVICE_ROLE = "device_role"
        private const val KEY_CAN_CONTROL = "can_control_device"
        private const val KEY_CAN_CONFIGURE = "can_configure_device"
        private const val KEY_CAN_UPGRADE = "can_upgrade_device"
        private const val KEY_EMAIL = "email"

        @Volatile
        private var INSTANCE: SessionManager? = null

        fun getInstance(context: Context): SessionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SessionManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
