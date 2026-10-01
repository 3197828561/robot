package com.robot.solar.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.robot.solar.repository.AuthRepository
import com.robot.solar.utils.LogUtils
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException

class LoginViewModel(application: Application) : AndroidViewModel(application) {

    private val authRepository = AuthRepository.getInstance(application)

    private val _navigateNext = MutableLiveData<Boolean>()
    val navigateNext: LiveData<Boolean> = _navigateNext

    private val _toastMessage = MutableLiveData<String?>()
    val toastMessage: LiveData<String?> = _toastMessage
    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading

    fun login(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _toastMessage.value = "邮箱或密码不能为空"
            return
        }
        if (_loading.value == true) return
        _loading.value = true
        viewModelScope.launch {
            authRepository.login(email, password)
                .onSuccess {
                    LogUtils.login("用户登录成功")
                    _navigateNext.postValue(true)
                }
                .onFailure {
                    _toastMessage.postValue(LoginErrorMessage.from(it))
                }
            _loading.postValue(false)
        }
    }

    fun consumeNavigate() { _navigateNext.value = false }
    fun consumeToast() { _toastMessage.value = null }
}

internal object LoginErrorMessage {
    fun from(error: Throwable): String = when {
        error is HttpException && error.code() == 401 -> "账号或密码错误"
        error is HttpException && error.code() == 403 -> "账号已停用或没有登录权限"
        error is IOException -> "无法连接服务器，请检查网络后重试"
        else -> "登录服务暂时不可用，请稍后重试"
    }
}
