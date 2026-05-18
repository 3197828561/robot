package com.robot.solar.demo.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.robot.solar.demo.repository.LoginRepository
import com.robot.solar.demo.utils.LogUtils
import kotlinx.coroutines.launch

/**
 * 登录页 ViewModel：承载表单校验、调用仓库、一次性事件（Toast / 导航）
 */
class LoginViewModel(application: Application) : AndroidViewModel(application) {

    private val loginRepository = LoginRepository.getInstance(application)

    private val _navigateHome = MutableLiveData<Boolean>()
    val navigateHome: LiveData<Boolean> = _navigateHome

    private val _toastMessage = MutableLiveData<String?>()
    val toastMessage: LiveData<String?> = _toastMessage

    /**
     * 用户点击登录：协程内访问仓库，避免阻塞主线程
     */
    fun login(username: String, password: String, remember: Boolean, autoLogin: Boolean) {
        if (autoLogin && !remember) {
            _toastMessage.value = "自动登录需要先勾选「记住密码」"
            return
        }
        viewModelScope.launch {
            when (val result = loginRepository.login(username, password, remember, autoLogin)) {
                LoginRepository.LoginResult.EmptyInput ->
                    _toastMessage.postValue("用户名或密码不能为空")

                LoginRepository.LoginResult.InvalidCredential ->
                    _toastMessage.postValue("账号或密码错误")

                LoginRepository.LoginResult.Success -> {
                    LogUtils.login("用户「${username.trim()}」登录成功")
                    _navigateHome.postValue(true)
                }
            }
        }
    }

    fun consumeNavigateHome() {
        _navigateHome.value = false
    }

    fun consumeToast() {
        _toastMessage.value = null
    }

    /** 供界面初始化勾选状态与预填账号 */
    fun isRememberPassword(): Boolean = loginRepository.isRememberPassword()

    fun isAutoLoginEnabled(): Boolean = loginRepository.isAutoLoginEnabled()

    fun getSavedUsername(): String = loginRepository.getSavedUsername()

    fun getSavedPassword(): String = loginRepository.getSavedPassword()
}
