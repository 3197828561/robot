package com.robot.solar.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.robot.solar.network.http.dto.DeviceDto
import com.robot.solar.repository.DeviceRepository
import kotlinx.coroutines.launch

class DeviceListViewModel(application: Application) : AndroidViewModel(application) {

    private val deviceRepository = DeviceRepository.getInstance(application)

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
                _devices.value = deviceRepository.fetchDevices()
            } catch (e: Exception) {
                _error.value = e.message ?: "加载设备失败"
            } finally {
                _loading.value = false
            }
        }
    }

    fun selectDevice(device: DeviceDto) {
        deviceRepository.selectDevice(device.deviceId, device.displayName, device.productType)
        _navigateMain.value = true
    }

    fun consumeNavigateMain() { _navigateMain.value = false }
    fun consumeError() { _error.value = null }
}
