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

    var deviceId: String?
        get() = prefs.getString(KEY_DEVICE_ID, null)
        set(value) = prefs.edit { putString(KEY_DEVICE_ID, value) }

    var deviceDisplayName: String?
        get() = prefs.getString(KEY_DEVICE_NAME, null)
        set(value) = prefs.edit { putString(KEY_DEVICE_NAME, value) }

    var productType: String?
        get() = prefs.getString(KEY_PRODUCT_TYPE, null)
        set(value) = prefs.edit { putString(KEY_PRODUCT_TYPE, value) }

    var userEmail: String?
        get() = prefs.getString(KEY_EMAIL, null)
        set(value) = prefs.edit { putString(KEY_EMAIL, value) }

    fun isLoggedIn(): Boolean = !accessToken.isNullOrBlank()

    fun hasSelectedDevice(): Boolean = !deviceId.isNullOrBlank()

    fun clear() {
        prefs.edit { clear() }
    }

    companion object {
        private const val PREFS = "solar_session"
        private const val KEY_TOKEN = "token"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_PRODUCT_TYPE = "product_type"
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
