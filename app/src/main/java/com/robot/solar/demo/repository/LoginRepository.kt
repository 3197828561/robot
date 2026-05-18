package com.robot.solar.demo.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 登录数据仓库：负责本地账号校验与「记住密码 / 自动登录」持久化
 *
 * 说明：Demo 固定账号 admin / 123456，密码以明文存于 SharedPreferences，仅用于教学演示。
 */
class LoginRepository private constructor(
    context: Context
) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 读取是否记住密码 */
    fun isRememberPassword(): Boolean = prefs.getBoolean(KEY_REMEMBER, false)

    /** 读取是否自动登录 */
    fun isAutoLoginEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_LOGIN, false)

    /** 读取上次保存的用户名 */
    fun getSavedUsername(): String = prefs.getString(KEY_USERNAME, "").orEmpty()

    /** 读取上次保存的密码（Demo 明文） */
    fun getSavedPassword(): String = prefs.getString(KEY_PASSWORD, "").orEmpty()

    /**
     * 尝试自动登录：在开启自动登录且账号密码与固定演示账号一致时返回 true
     */
    fun tryAutoLogin(): Boolean {
        if (!isAutoLoginEnabled()) return false
        val u = getSavedUsername()
        val p = getSavedPassword()
        return u == DEMO_USER && p == DEMO_PASS
    }

    /**
     * 执行登录校验并可选地持久化凭据
     */
    suspend fun login(
        username: String,
        password: String,
        remember: Boolean,
        autoLogin: Boolean
    ): LoginResult = withContext(Dispatchers.IO) {
        val trimmedUser = username.trim()
        val trimmedPass = password.trim()
        when {
            trimmedUser.isEmpty() || trimmedPass.isEmpty() ->
                LoginResult.EmptyInput

            trimmedUser != DEMO_USER || trimmedPass != DEMO_PASS ->
                LoginResult.InvalidCredential

            else -> {
                prefs.edit {
                    putBoolean(KEY_REMEMBER, remember)
                    putBoolean(KEY_AUTO_LOGIN, autoLogin)
                    if (remember) {
                        putString(KEY_USERNAME, trimmedUser)
                        putString(KEY_PASSWORD, trimmedPass)
                    } else {
                        remove(KEY_USERNAME)
                        remove(KEY_PASSWORD)
                        // 未记住密码时不应自动登录
                        putBoolean(KEY_AUTO_LOGIN, false)
                    }
                }
                LoginResult.Success
            }
        }
    }

    /**
     * 退出登录时清除自动登录（保留记住密码由产品决定，此处清除自动登录标志更安全）
     */
    fun clearAutoLoginFlag() {
        prefs.edit { putBoolean(KEY_AUTO_LOGIN, false) }
    }

    sealed class LoginResult {
        data object Success : LoginResult()
        data object EmptyInput : LoginResult()
        data object InvalidCredential : LoginResult()
    }

    companion object {
        private const val PREFS_NAME = "solar_login_prefs"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_REMEMBER = "remember"
        private const val KEY_AUTO_LOGIN = "auto_login"

        /** 固定演示账号 */
        const val DEMO_USER: String = "admin"

        /** 固定演示密码 */
        const val DEMO_PASS: String = "123456"

        @Volatile
        private var INSTANCE: LoginRepository? = null

        fun getInstance(context: Context): LoginRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LoginRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
