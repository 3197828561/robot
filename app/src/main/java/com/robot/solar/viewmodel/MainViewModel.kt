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
import com.robot.solar.network.mqtt.MapLoadStatus
import com.robot.solar.network.mqtt.MapUiState
import com.robot.solar.network.mqtt.MissionCommandPayloads
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
                connected = mqttConnected.value == true,
                online = deviceOnline.value == true,
                status = status.value,
                mapState = mapState.value
            )
        }
        addSource(mqttConnected) { refresh() }
        addSource(deviceOnline) {
            if (it != true) stopRemote(sendZero = true)
            refresh()
        }
        addSource(status) {
            if (it?.operationalMode != "manual" || it.safetyState in REMOTE_BLOCKING_SAFETY_STATES) {
                stopRemote(sendZero = true)
            }
            refresh()
        }
        addSource(mapState) { refresh() }
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
    private var mqttShutdown = false
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

    fun enterRemoteMode() {
        if (mqttConnected.value == true && deviceOnline.value == true && status.value?.operationalMode != "manual") {
            dispatchCmd("切换手动模式", "manual", applyDebounce = false)
        }
    }

    fun leaveRemoteMode() {
        stopRemote(sendZero = false)
        if (mqttConnected.value == true && deviceOnline.value == true) {
            dispatchCmd("切回自动模式", "auto", applyDebounce = false) {
                mqtt.publishRemote(0.0, 0.0)
            }
        }
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
        dispatchCmd(label, action, applyDebounce = true)
    }

    private fun dispatchCmd(
        label: String,
        action: String,
        applyDebounce: Boolean,
        beforePublish: (() -> Unit)? = null
    ) {
        if (applyDebounce && !debounce()) return
        if (!canSendCommand(action)) {
            _commandState.value = CommandUiState(null, action, CommandStatus.FAILED, "$label 当前不可用")
            return
        }
        val params = commandParams(action) ?: run {
            _commandState.value = CommandUiState(null, action, CommandStatus.FAILED, "$label 缺少地图或任务状态")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            beforePublish?.invoke()
            val result = mqtt.publishCmd(action, params)
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
                LogUtils.device("命令发送失败：$label")
            }
        }
    }

    private fun commandParams(action: String): Map<String, Any?>? = when (action) {
        "start" -> buildCoverageStartParams(mapState.value)
        "stop", "pause", "resume", "replan" -> MissionCommandPayloads.targetMission(status.value?.missionId)
        "manual", "auto", "estop", "clear_estop" -> emptyMap()
        else -> null
    }

    private fun buildCoverageStartParams(state: MapUiState?): Map<String, Any?>? {
        if (state?.status != MapLoadStatus.READY) return null
        val metadata = state.map ?: return null
        val map = state.pvMap ?: return null
        val mapId = metadata.mapId ?: return null
        val mapVersion = metadata.mapVersion ?: return null
        if (map.mapId != mapId || map.version != mapVersion) return null
        return MissionCommandPayloads.coverageStart(
            mapId = mapId,
            mapVersion = mapVersion,
            cleanableBlockIds = map.blocks.filter { it.cleanable }.map { it.blockId }
        )
    }

    private fun handleCmdAck(ack: CmdAckMessage?) {
        ack ?: return
        val ackResult = if (ack.ackStatus == "success") CommandStatus.SUCCESS else CommandStatus.FAILED
        if (waitingCmdId != null && ack.cmdId == waitingCmdId) {
            finishPendingCommand(ackResult, null, ack.errorCode)
        } else {
            _commandState.postValue(CommandUiState(ack.cmdId, ack.cmd, ackResult, errorCode = ack.errorCode))
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
        val availability = controlsEnabled.value ?: computeAvailability(true, true, status.value, mapState.value)
        return when (action) {
            "start" -> availability.canStart
            "stop" -> availability.canStop
            "pause" -> availability.canPause
            "resume" -> availability.canResume
            "replan" -> availability.canReplan
            "manual", "auto" -> true
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
        if (mqttShutdown) return
        mqttShutdown = true
        stopRemote(sendZero = false)
        commandTimeoutJob?.cancel()
        if (mqttConnected.value == true) {
            mqtt.publishRemote(0.0, 0.0)
            if (status.value?.operationalMode == "manual") {
                mqtt.publishCmd("auto", emptyMap())
            }
        }
        mqtt.shutdown()
    }

    fun retryMapDownload() = mqtt.retryMapDownload()

    private fun isRemoteAllowed(connected: Boolean, online: Boolean, status: StatusMessage?): Boolean {
        return ManualControlPolicy.isAllowed(connected, online) &&
            status?.operationalMode == "manual" &&
            status.safetyState !in REMOTE_BLOCKING_SAFETY_STATES
    }

    private fun computeAvailability(
        connected: Boolean,
        online: Boolean,
        status: StatusMessage?,
        mapState: MapUiState?
    ): ControlAvailability {
        if (!connected || !online) return ControlAvailability()
        val missionId = status?.missionId?.takeIf { it.isNotBlank() }
        val runState = status?.runState
        val activeMission = missionId != null && runState !in TERMINAL_RUN_STATES
        val startReady = !activeMission && buildCoverageStartParams(mapState) != null
        return ControlAvailability(
            canStart = startReady,
            canStop = activeMission,
            canPause = missionId != null && runState in setOf("starting", "running"),
            canResume = missionId != null && runState == "paused",
            canReplan = activeMission && status?.taskKind == "coverage",
            canEstop = true,
            canClearEstop = status?.safetyState in setOf("estop", "clearing_estop"),
            canRemote = isRemoteAllowed(connected, online, status)
        )
    }

    companion object {
        private val TERMINAL_RUN_STATES = setOf("idle", "succeeded", "failed", "canceled")
        private val REMOTE_BLOCKING_SAFETY_STATES = setOf("estop", "clearing_estop", "fault")
    }
}

data class ControlAvailability(
    val canStart: Boolean = false,
    val canStop: Boolean = false,
    val canPause: Boolean = false,
    val canResume: Boolean = false,
    val canReplan: Boolean = false,
    val canEstop: Boolean = false,
    val canClearEstop: Boolean = false,
    val canRemote: Boolean = false
)
