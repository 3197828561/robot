package com.robot.solar.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.robot.solar.repository.AuthRepository
import com.robot.solar.utils.LogUtils
import kotlinx.coroutines.launch

class LoginViewModel(application: Application) : AndroidViewModel(application) {

    private val authRepository = AuthRepository.getInstance(application)

    private val _navigateNext = MutableLiveData<Boolean>()
    val navigateNext: LiveData<Boolean> = _navigateNext

    private val _toastMessage = MutableLiveData<String?>()
    val toastMessage: LiveData<String?> = _toastMessage

    fun login(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _toastMessage.value = "邮箱或密码不能为空"
            return
        }
        viewModelScope.launch {
            authRepository.login(email, password)
                .onSuccess {
                    LogUtils.login("用户登录成功")
                    _navigateNext.postValue(true)
                }
                .onFailure {
                    _toastMessage.postValue("登录失败：${it.message ?: "网络错误"}")
                }
        }
    }

    fun consumeNavigate() { _navigateNext.value = false }
    fun consumeToast() { _toastMessage.value = null }
}
