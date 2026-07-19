package com.robot.solar.viewmodel

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.viewModelScope
import com.robot.solar.network.mqtt.CloudCommMqttManager
import com.robot.solar.network.mqtt.CmdAckMessage
import com.robot.solar.network.mqtt.CommandStatus
import com.robot.solar.network.mqtt.CommandUiState
import com.robot.solar.network.mqtt.MapUiState
import com.robot.solar.network.mqtt.PoseMessage
import com.robot.solar.network.mqtt.StatusMessage
import com.robot.solar.repository.DeviceRepository
import com.robot.solar.ui.main.ManualDirection
import com.robot.solar.utils.LogUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val mqtt = CloudCommMqttManager.getInstance(application)
    private val deviceRepository = DeviceRepository.getInstance(application)

    val mqttConnected: LiveData<Boolean> = mqtt.mqttConnected
    val deviceOnline: LiveData<Boolean?> = mqtt.deviceOnline
    val batteryPercent: LiveData<Int?> = mqtt.batteryPercent
    val status: LiveData<StatusMessage?> = mqtt.status
    val lastHeartbeatAt: LiveData<Long?> = mqtt.lastHeartbeatAt
    val mapState: LiveData<MapUiState> = mqtt.mapState
    val pose: LiveData<PoseMessage?> = mqtt.pose

    private val _commandState = MutableLiveData(CommandUiState(null, null, CommandStatus.IDLE))
    val commandState: LiveData<CommandUiState> = _commandState

    val controlsEnabled = MediatorLiveData<ControlAvailability>().apply {
        fun refresh() {
            value = computeAvailability(
                mqttConnected.value == true,
                deviceOnline.value == true,
                status.value
            )
        }
        addSource(mqttConnected) { refresh() }
        addSource(deviceOnline) {
            if (it != true) stopRemote(sendZero = true)
            refresh()
        }
        addSource(status) {
            if (!isRemoteAllowed(mqttConnected.value == true, deviceOnline.value == true, it)) {
                stopRemote(sendZero = true)
            }
            refresh()
        }
    }

    val deviceDisplayName: String?
        get() = deviceRepository.currentDeviceName()
    val deviceId: String?
        get() = deviceRepository.currentDeviceId()
    val productType: String?
        get() = deviceRepository.currentProductType()

    private var lastCommandUptime: Long = 0L
    private var waitingCmdId: String? = null
    private var commandTimeoutJob: Job? = null
    private var remoteJob: Job? = null
    private var currentDirection: ManualDirection? = null
    private val cmdAckObserver = Observer<CmdAckMessage?> { handleCmdAck(it) }
    private val mqttConnectedObserver = Observer<Boolean> { connected ->
        if (connected != true) {
            stopRemote(sendZero = false)
            if (waitingCmdId != null) {
                finishPendingCommand(CommandStatus.CONNECTION_LOST, "MQTT 连接已断开", null)
            }
        }
    }

    init {
        mqtt.lastCmdAck.observeForever(cmdAckObserver)
        mqtt.mqttConnected.observeForever(mqttConnectedObserver)
    }

    fun onScreenReady() {
        val identity = deviceRepository.currentMqttIdentity()
        mqtt.start(identity.deviceId, identity.productType)
    }

    fun startRemote(direction: ManualDirection) {
        if (!isRemoteAllowed(mqttConnected.value == true, deviceOnline.value == true, status.value)) return
        if (remoteJob?.isActive == true && currentDirection == direction) return
        stopRemote(sendZero = remoteJob?.isActive == true)
        currentDirection = direction
        remoteJob = viewModelScope.launch(Dispatchers.IO) {
            delay(500)
            while (true) {
                if (!isRemoteAllowed(mqttConnected.value == true, deviceOnline.value == true, status.value)) break
                val active = currentDirection ?: break
                mqtt.publishRemote(active.linearSpeedCms, active.angularSpeedRadps)
                delay(50)
            }
            mqtt.publishRemote(0.0, 0.0)
        }
    }

    fun stopRemote(sendZero: Boolean = true) {
        val wasActive = remoteJob?.isActive == true
        remoteJob?.cancel()
        remoteJob = null
        currentDirection = null
        if (sendZero && wasActive && mqttConnected.value == true) {
            viewModelScope.launch(Dispatchers.IO) { mqtt.publishRemote(0.0, 0.0) }
        }
    }

    fun ordinaryRemoteStop() {
        val hadActiveControl = remoteJob?.isActive == true
        stopRemote(sendZero = false)
        if (mqttConnected.value == true && (hadActiveControl || deviceOnline.value == true)) {
            viewModelScope.launch(Dispatchers.IO) { mqtt.publishRemote(0.0, 0.0) }
        }
    }

    fun sendCmd(label: String, action: String) {
        if (!debounce()) return
        if (waitingCmdId != null && action != "estop") return
        if (!canSendCommand(action)) return
        viewModelScope.launch(Dispatchers.IO) {
            val result = mqtt.publishCmd(action)
            if (result.published && result.cmdId != null) {
                waitingCmdId = result.cmdId
                _commandState.postValue(CommandUiState(result.cmdId, action, CommandStatus.SENDING, "$label 发送中"))
                commandTimeoutJob?.cancel()
                commandTimeoutJob = viewModelScope.launch {
                    delay(5000)
                    if (waitingCmdId == result.cmdId) {
                        finishPendingCommand(CommandStatus.TIMEOUT, "$label 回执超时", null)
                    }
                }
                LogUtils.device("已发送操作：$label")
            } else {
                _commandState.postValue(CommandUiState(null, action, CommandStatus.FAILED, "$label 发送失败"))
                LogUtils.device("命令失败：$label")
            }
        }
    }

    private fun handleCmdAck(ack: CmdAckMessage?) {
        ack ?: return
        val pending = waitingCmdId
        if (pending != null && ack.cmdId == pending) {
            val status = if (ack.ackStatus == "success") CommandStatus.SUCCESS else CommandStatus.FAILED
            finishPendingCommand(status, null, null)
        } else {
            val status = if (ack.ackStatus == "success") CommandStatus.SUCCESS else CommandStatus.FAILED
            _commandState.postValue(CommandUiState(ack.cmdId, ack.cmd, status))
        }
    }

    private fun finishPendingCommand(status: CommandStatus, message: String?, errorCode: String?) {
        val cmdId = waitingCmdId
        val cmd = _commandState.value?.cmd
        waitingCmdId = null
        commandTimeoutJob?.cancel()
        _commandState.postValue(CommandUiState(cmdId, cmd, status, message, errorCode))
    }

    private fun canSendCommand(action: String): Boolean {
        if (mqttConnected.value != true || deviceOnline.value != true) return false
        val availability = computeAvailability(true, true, status.value)
        return when (action) {
            "start" -> availability.canStart
            "stop" -> availability.canStop
            "estop" -> availability.canEstop
            "clear_estop" -> availability.canClearEstop
            else -> false
        }
    }

    private fun debounce(ms: Long = 400L): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastCommandUptime < ms) return false
        lastCommandUptime = now
        return true
    }

    override fun onCleared() {
        stopRemote(sendZero = true)
        commandTimeoutJob?.cancel()
        mqtt.lastCmdAck.removeObserver(cmdAckObserver)
        mqtt.mqttConnected.removeObserver(mqttConnectedObserver)
        super.onCleared()
    }

    fun shutdownMqtt() {
        stopRemote(sendZero = true)
        commandTimeoutJob?.cancel()
        mqtt.shutdown()
    }

    fun retryMapDownload() = mqtt.retryMapDownload()

    private fun isRemoteAllowed(connected: Boolean, online: Boolean, status: StatusMessage?): Boolean {
        return ManualControlPolicy.isAllowed(connected, online, status)
    }

    private fun computeAvailability(
        connected: Boolean,
        online: Boolean,
        status: StatusMessage?
    ): ControlAvailability {
        if (!connected || !online) return ControlAvailability()
        val work = status?.workStatus
        val device = status?.deviceStatus
        if (device == "fault") return ControlAvailability(canEstop = true)
        return when (work) {
            "running" -> ControlAvailability(canStop = true, canEstop = true)
            "stopped" -> ControlAvailability(
                canStart = true,
                canEstop = true,
                canRemote = device == "normal" && status.controlMode == "manual"
            )
            "idle", null -> ControlAvailability(canStart = true, canEstop = true)
            "estopped" -> ControlAvailability(canClearEstop = true)
            "fault" -> ControlAvailability(canEstop = true)
            else -> ControlAvailability(canEstop = true)
        }
    }
}

data class ControlAvailability(
    val canStart: Boolean = false,
    val canStop: Boolean = false,
    val canEstop: Boolean = false,
    val canClearEstop: Boolean = false,
    val canRemote: Boolean = false
)
