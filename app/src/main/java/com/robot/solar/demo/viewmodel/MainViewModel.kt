package com.robot.solar.demo.viewmodel

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.robot.solar.demo.network.MqttManager
import com.robot.solar.demo.utils.LogUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 主页 ViewModel：聚合 MQTT 状态、防抖发送控制指令、写入设备操作日志
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val mqttManager = MqttManager.getInstance(application)

    val mqttConnected: LiveData<Boolean> = mqttManager.mqttConnected
    val deviceOnline: LiveData<Boolean?> = mqttManager.deviceOnline
    val batteryPercent: LiveData<Int?> = mqttManager.batteryPercent

    private val _simulatedBattery = MutableLiveData(78)
    val simulatedBattery: LiveData<Int> = _simulatedBattery

    private var lastCommandUptime: Long = 0L

    /**
     * 进入主页后启动 MQTT；协程仅用于触发管理器内部 IO
     */
    fun onScreenReady() {
        mqttManager.start()
    }

    /**
     * 带防抖的指令发送，避免连点造成消息风暴
     */
    fun sendRobotCommand(label: String, cmd: String, debounceMs: Long = 500L) {
        val now = SystemClock.uptimeMillis()
        if (now - lastCommandUptime < debounceMs) {
            return
        }
        lastCommandUptime = now
        viewModelScope.launch(Dispatchers.IO) {
            val ok = mqttManager.publishCommandJson(cmd)
            if (ok) {
                LogUtils.device("遥控指令：$label（cmd=$cmd）已发送至 MQTT")
            } else {
                LogUtils.device("遥控指令：$label（cmd=$cmd）发送失败（未连接或异常）")
            }
        }
    }
}
