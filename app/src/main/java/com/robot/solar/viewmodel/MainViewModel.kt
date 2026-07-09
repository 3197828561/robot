package com.robot.solar.viewmodel

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.robot.solar.network.mqtt.CloudCommMqttManager
import com.robot.solar.network.mqtt.StatusMessage
import com.robot.solar.repository.DeviceRepository
import com.robot.solar.utils.LogUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val mqtt = CloudCommMqttManager.getInstance(application)
    private val deviceRepository = DeviceRepository.getInstance(application)

    val mqttConnected: LiveData<Boolean> = mqtt.mqttConnected
    val deviceOnline: LiveData<Boolean?> = mqtt.deviceOnline
    val batteryPercent: LiveData<Int?> = mqtt.batteryPercent
    val status: LiveData<StatusMessage?> = mqtt.status
    val lastCmdFeedback: LiveData<String?> = mqtt.lastCmdFeedback

    val deviceDisplayName: String?
        get() = deviceRepository.currentDeviceName()

    private var lastCommandUptime: Long = 0L

    fun onScreenReady() {
        val identity = deviceRepository.currentMqttIdentity()
        mqtt.start(identity.deviceId, identity.productType)
    }

    fun sendRemote(label: String, linear: Double, angular: Double) {
        if (!debounce()) return
        viewModelScope.launch(Dispatchers.IO) {
            val ok = mqtt.publishRemote(linear, angular)
            if (ok) LogUtils.device("遥控：$label")
            else LogUtils.device("遥控失败：$label")
        }
    }

    fun sendCmd(label: String, action: String) {
        if (!debounce()) return
        viewModelScope.launch(Dispatchers.IO) {
            val ok = mqtt.publishCmd(action)
            if (ok) LogUtils.device("命令：$label ($action)")
            else LogUtils.device("命令失败：$label")
        }
    }

    private fun debounce(ms: Long = 400L): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastCommandUptime < ms) return false
        lastCommandUptime = now
        return true
    }
}
