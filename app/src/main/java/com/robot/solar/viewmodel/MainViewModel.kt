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
import com.robot.solar.network.mqtt.CoverageCommandParams
import com.robot.solar.network.mqtt.MapUiState
import com.robot.solar.network.mqtt.MissionState
import com.robot.solar.network.mqtt.PoseMessage
import com.robot.solar.network.mqtt.PreparedCommand
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
    val missionState: LiveData<MissionState> = mqtt.missionState
    val lastHeartbeatAt: LiveData<Long?> = mqtt.lastHeartbeatAt
    val mapState: LiveData<MapUiState> = mqtt.mapState
    val pose: LiveData<PoseMessage?> = mqtt.pose

    private val _commandState = MutableLiveData(CommandUiState(null, null, CommandStatus.IDLE))
    val commandState: LiveData<CommandUiState> = _commandState
    private val _remoteModeAccepted = MutableLiveData(false)

    val controlsEnabled = MediatorLiveData<ControlAvailability>().apply {
        fun refresh() {
            value = computeAvailability(
                mqttConnected.value == true,
                deviceOnline.value == true
            )
        }
        addSource(mqttConnected) { refresh() }
        addSource(deviceOnline) {
            if (it != true) stopRemote(sendZero = true)
            refresh()
        }
        addSource(status) {
            refresh()
        }
        addSource(missionState) {
            refresh()
        }
        addSource(_remoteModeAccepted) {
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
    private var lastPreparedCommand: PreparedCommand? = null
    private var lastCommandLabel: String? = null
    private var pendingExitToAuto = false
    private var remoteJob: Job? = null
    private var currentDirection: ManualDirection? = null
    private val cmdAckObserver = Observer<CmdAckMessage?> { handleCmdAck(it) }
    private val mqttConnectedObserver = Observer<Boolean> { connected ->
        if (connected != true) {
            _remoteModeAccepted.postValue(false)
            stopRemote(sendZero = false)
            if (waitingCmdId != null) {
                finishPendingCommand(CommandStatus.CONNECTION_LOST, "MQTT 连接已断开", null)
            }
        }
    }
    private val missionStateObserver = Observer<MissionState> { state ->
        if (state.operationalMode != "manual" || state.safetyState != "normal") {
            stopRemote(sendZero = true)
        }
        if (state.operationalMode == "auto") {
            _remoteModeAccepted.postValue(false)
            pendingExitToAuto = false
        } else if (
            pendingExitToAuto &&
            state.operationalMode == "manual" &&
            waitingCmdId == null &&
            mqttConnected.value == true &&
            deviceOnline.value == true
        ) {
            sendPreparedCommand("切回自动模式", "auto")
        }
    }

    init {
        mqtt.lastCmdAck.observeForever(cmdAckObserver)
        mqtt.mqttConnected.observeForever(mqttConnectedObserver)
        mqtt.missionState.observeForever(missionStateObserver)
    }

    fun onScreenReady() {
        val identity = deviceRepository.currentMqttIdentity()
        mqtt.start(identity.deviceId, identity.productType)
    }

    fun startRemote(direction: ManualDirection) {
        if (!isRemoteAllowed(mqttConnected.value == true, deviceOnline.value == true)) return
        if (remoteJob?.isActive == true && currentDirection == direction) return
        stopRemote(sendZero = remoteJob?.isActive == true)
        currentDirection = direction
        remoteJob = viewModelScope.launch(Dispatchers.IO) {
            delay(500)
            while (true) {
                if (!isRemoteAllowed(mqttConnected.value == true, deviceOnline.value == true)) break
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

    fun startCoverage() {
        val map = mapState.value?.pvMap
        if (map == null) {
            rejectCommand("start", "请先加载有效地图")
            return
        }
        if (map.mapId !in 0..UINT32_MAX || map.version < 0) {
            rejectCommand("start", "地图编号或版本超出协议范围")
            return
        }
        val targets = map.blocks.filter { it.cleanable && it.blockId > 0 }.map { it.blockId }.distinct()
        if (targets.isEmpty()) {
            rejectCommand("start", "当前地图没有可清洁区域")
            return
        }
        val coverage = CoverageCommandParams(
            mapId = map.mapId,
            mapVersion = map.version,
            useCurrentPose = true,
            targetBlockIds = targets,
            globalPlan = true
        )
        sendPreparedCommand(
            label = "开始覆盖任务",
            action = "start",
            params = mapOf("taskKind" to "coverage", "coverage" to coverage)
        )
    }

    fun sendMissionCommand(label: String, action: String) {
        val missionId = missionState.value?.missionId?.takeIf { it.isNotBlank() }
        if (missionId == null) {
            rejectCommand(action, "当前没有可操作的任务")
            return
        }
        sendPreparedCommand(label, action, mapOf("targetMissionId" to missionId))
    }

    fun sendCmd(label: String, action: String) {
        sendPreparedCommand(label, action)
    }

    fun enterRemoteMode() {
        pendingExitToAuto = false
        _remoteModeAccepted.value = false
        if (missionState.value?.safetyState != "normal") {
            rejectCommand("manual", "当前安全状态不允许进入手动模式")
            return
        }
        sendPreparedCommand("切换手动模式", "manual")
    }

    fun exitRemoteMode() {
        ordinaryRemoteStop()
        _remoteModeAccepted.value = false
        pendingExitToAuto = true
        if (
            missionState.value?.operationalMode == "manual" &&
            waitingCmdId == null &&
            mqttConnected.value == true &&
            deviceOnline.value == true
        ) {
            sendPreparedCommand("切回自动模式", "auto")
        }
    }

    fun retryLastCommand() {
        val command = lastPreparedCommand ?: return
        val label = lastCommandLabel ?: command.cmd
        if (waitingCmdId != null || mqttConnected.value != true || deviceOnline.value != true) return
        publishPreparedCommand(label, command)
    }

    private fun sendPreparedCommand(
        label: String,
        action: String,
        params: Any = emptyMap<String, Any?>()
    ) {
        if (!debounce()) return
        if (!canSendCommand(action)) return
        if (waitingCmdId != null) {
            rejectCommand(action, "上一条命令仍在等待回执")
            return
        }
        val command = mqtt.prepareCommand(action, params)
        if (command == null) {
            rejectCommand(action, "$label 参数无效")
            return
        }
        lastPreparedCommand = command
        lastCommandLabel = label
        publishPreparedCommand(label, command)
    }

    private fun publishPreparedCommand(label: String, command: PreparedCommand) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = mqtt.publishCmd(command)
            if (result.published && result.cmdId != null) {
                waitingCmdId = result.cmdId
                _commandState.postValue(CommandUiState(result.cmdId, command.cmd, CommandStatus.SENDING, "$label 发送中"))
                commandTimeoutJob?.cancel()
                commandTimeoutJob = viewModelScope.launch {
                    delay(5000)
                    if (waitingCmdId == result.cmdId) {
                        finishPendingCommand(CommandStatus.TIMEOUT, "$label 回执超时", null)
                    }
                }
                LogUtils.device("已发送操作：$label")
            } else {
                _commandState.postValue(CommandUiState(command.cmdId, command.cmd, CommandStatus.FAILED, "$label 发送失败"))
                LogUtils.device("命令失败：$label")
            }
        }
    }

    private fun rejectCommand(action: String, message: String) {
        _commandState.postValue(CommandUiState(null, action, CommandStatus.FAILED, message))
    }

    private fun handleCmdAck(ack: CmdAckMessage?) {
        ack ?: return
        val pending = waitingCmdId
        if (pending != null && ack.cmdId == pending) {
            val status = if (ack.ackStatus == "success") CommandStatus.SUCCESS else CommandStatus.FAILED
            if (ack.cmd == "manual") {
                _remoteModeAccepted.postValue(status == CommandStatus.SUCCESS)
            } else if (ack.cmd == "auto" && status == CommandStatus.SUCCESS) {
                _remoteModeAccepted.postValue(false)
            }
            finishPendingCommand(status, null, ack.errorCode)
            if (ack.cmd == "manual" && status == CommandStatus.SUCCESS && pendingExitToAuto) {
                viewModelScope.launch {
                    delay(400)
                    if (
                        pendingExitToAuto &&
                        missionState.value?.operationalMode == "manual" &&
                        waitingCmdId == null
                    ) {
                        sendPreparedCommand("切回自动模式", "auto")
                    }
                }
            }
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
        return action in SUPPORTED_COMMANDS
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
        mqtt.missionState.removeObserver(missionStateObserver)
        super.onCleared()
    }

    fun shutdownMqtt() {
        stopRemote(sendZero = true)
        commandTimeoutJob?.cancel()
        mqtt.shutdown()
    }

    fun retryMapDownload() = mqtt.retryMapDownload()

    private fun isRemoteAllowed(connected: Boolean, online: Boolean): Boolean {
        val mission = missionState.value
        return ManualControlPolicy.isAllowed(
            connected = connected,
            online = online,
            operationalMode = mission?.operationalMode,
            safetyState = mission?.safetyState,
            manualCommandAccepted = _remoteModeAccepted.value == true
        )
    }

    private fun computeAvailability(
        connected: Boolean,
        online: Boolean
    ): ControlAvailability {
        if (!connected || !online) return ControlAvailability()
        val mission = missionState.value ?: MissionState()
        val safety = mission.safetyState
        val runState = mission.runState
        val activeMission = !mission.missionId.isNullOrBlank() &&
            runState in setOf("starting", "running", "paused")
        val safeForMission = safety == "normal"
        return ControlAvailability(
            canStart = safeForMission && !activeMission && mission.operationalMode == "auto",
            canStop = activeMission,
            canPause = runState in setOf("starting", "running"),
            canResume = runState == "paused",
            canReplan = activeMission && mission.taskKind == "coverage",
            canEstop = safety !in setOf("estop", "clearing_estop"),
            canClearEstop = safety == "estop",
            canRemote = isRemoteAllowed(connected, online)
        )
    }

    companion object {
        private const val UINT32_MAX = 4_294_967_295L
        private val SUPPORTED_COMMANDS = setOf(
            "start",
            "stop",
            "pause",
            "resume",
            "replan",
            "manual",
            "auto",
            "estop",
            "clear_estop"
        )
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
