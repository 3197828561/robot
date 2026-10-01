package com.robot.solar.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.robot.solar.data.session.SessionManager
import com.robot.solar.network.http.dto.DeviceDto
import com.robot.solar.repository.DeviceRepository
import kotlinx.coroutines.launch

class DeviceListViewModel(application: Application) : AndroidViewModel(application) {

    private val deviceRepository = DeviceRepository.getInstance(application)
    private val session = SessionManager.getInstance(application)

    private val _devices = MutableLiveData<List<DeviceDto>>()
    val devices: LiveData<List<DeviceDto>> = _devices

    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private val _navigateMain = MutableLiveData<Boolean>()
    val navigateMain: LiveData<Boolean> = _navigateMain

    fun loadDevices() {
        viewModelScope.launch {
            _loading.value = true
            try {
                // 旧版/未知设备不能进入主界面，否则会回退到默认 crawler 身份，
                // 造成设备名称、地图和 MQTT 控制对象不一致。
                _devices.value = deviceRepository.fetchDevices()
                    .filter(deviceRepository::isSupportedDevice)
            } catch (e: Exception) {
                if (session.isLoggedIn()) {
                    _error.value = "加载设备失败，请检查网络后重试"
                }
            } finally {
                _loading.value = false
            }
        }
    }

    fun selectDevice(device: DeviceDto) {
        deviceRepository.selectDevice(device)
        _navigateMain.value = true
    }

    fun consumeNavigateMain() { _navigateMain.value = false }
    fun consumeError() { _error.value = null }
}
