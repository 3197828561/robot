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
import com.robot.solar.network.mqtt.CoverageTaskSelection
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
    private val _awaitingStartStatus = MutableLiveData(false)
    val awaitingStartStatus: LiveData<Boolean> = _awaitingStartStatus
    private val _awaitingClearEstopStatus = MutableLiveData(false)
    val awaitingClearEstopStatus: LiveData<Boolean> = _awaitingClearEstopStatus
    private val _retryAvailable = MutableLiveData(false)
    private val _commandInFlight = MutableLiveData(false)

    val controlsEnabled = MediatorLiveData<ControlAvailability>().apply {
        fun refresh() {
            value = computeAvailability(
                mqttConnected.value == true,
                deviceOnline.value == true
            )
        }
        addSource(mqttConnected) { refresh() }
        addSource(deviceOnline) {
            if (it != true) {
                _remoteModeAccepted.value = false
                stopRemote(sendZero = true)
            }
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
        addSource(_awaitingStartStatus) {
            refresh()
        }
        addSource(_awaitingClearEstopStatus) {
            refresh()
        }
        addSource(_retryAvailable) {
            refresh()
        }
        addSource(_commandInFlight) {
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
    private var waitingCommand: PreparedCommand? = null
    private var commandTimeoutJob: Job? = null
    private var lastPreparedCommand: PreparedCommand? = null
    private var lastCommandLabel: String? = null
    private var lastCommandParamsSummary: String? = null
    private var pendingCommandParamsSummary: String? = null
    private var pendingExitToAuto = false
    private var lastMissionErrorSignature: String? = null
    private var remoteJob: Job? = null
    private var currentDirection: ManualDirection? = null
    private val cmdAckObserver = Observer<CmdAckMessage?> { handleCmdAck(it) }
    private val mqttConnectedObserver = Observer<Boolean> { connected ->
        if (connected != true) {
            _remoteModeAccepted.postValue(false)
            _awaitingStartStatus.postValue(false)
            _awaitingClearEstopStatus.postValue(false)
            stopRemote(sendZero = false)
            if (waitingCmdId != null) {
                finishPendingCommand(CommandStatus.CONNECTION_LOST, "MQTT 连接已断开", null)
            }
        }
    }
    private val missionStateObserver = Observer<MissionState> { state ->
        if (
            !state.missionId.isNullOrBlank() &&
            state.runState in setOf("starting", "running", "paused", "succeeded", "failed", "canceled")
        ) {
            _awaitingStartStatus.postValue(false)
        }
        if (state.safetyState == "normal") {
            _awaitingClearEstopStatus.postValue(false)
        }
        val errorSignature = listOf(
            state.missionId,
            state.errorCode,
            state.errorRetryable,
            state.errorSource,
            state.errorMessage
        ).joinToString("|")
        if (
            errorSignature != lastMissionErrorSignature &&
            (state.errorCode != null && state.errorCode != 0 || !state.errorMessage.isNullOrBlank())
        ) {
            LogUtils.device(
                "任务错误：code=${state.errorCode ?: "--"} " +
                    "retryable=${state.errorRetryable ?: "--"} " +
                    "source=${state.errorSource.orEmpty()} ${state.errorMessage.orEmpty()}"
            )
        }
        lastMissionErrorSignature = errorSignature
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

    fun startCoverage(selection: CoverageTaskSelection) {
        val map = mapState.value?.pvMap
        if (map == null) {
            rejectCommand("start", "请先加载有效地图")
            return
        }
        if (map.mapId !in 0..UINT32_MAX || map.version !in 0..UINT32_MAX) {
            rejectCommand("start", "地图编号或版本超出协议范围")
            return
        }
        val targets = selection.targetBlockIds
        if (targets.isEmpty()) {
            rejectCommand("start", "至少选择一个目标区域")
            return
        }
        if (targets.any { it <= 0 } || targets.size != targets.distinct().size) {
            rejectCommand("start", "目标区域编号必须大于 0 且不能重复")
            return
        }
        if (targets.any { map.blocksById[it]?.cleanable != true }) {
            rejectCommand("start", "目标区域不存在或不可清洁")
            return
        }
        val start = selection.start
        if (!selection.useCurrentPose) {
            if (start == null) {
                rejectCommand("start", "未使用当前位置时必须填写起点")
                return
            }
            val block = map.blocksById[start.blockId]
            val cell = map.cellsByIndex[Triple(start.blockId, start.cellRow, start.cellCol)]
            if (block == null || cell == null) {
                rejectCommand("start", "起点 block/cell 不存在")
                return
            }
            if (
                start.innerRow !in 0 until map.cellModel.innerRows ||
                start.innerCol !in 0 until map.cellModel.innerCols
            ) {
                rejectCommand("start", "起点内部行列超出地图范围")
                return
            }
            if (start.heading !in 0..3) {
                rejectCommand("start", "起点 heading 必须为 0..3")
                return
            }
        }
        val coverage = CoverageCommandParams(
            mapId = map.mapId,
            mapVersion = map.version,
            useCurrentPose = selection.useCurrentPose,
            start = if (selection.useCurrentPose) null else start,
            targetBlockIds = targets,
            globalPlan = selection.globalPlan
        )
        val summary = buildString {
            append("map=${map.mapId}/v${map.version}")
            append(", currentPose=${selection.useCurrentPose}")
            if (!selection.useCurrentPose && start != null) {
                append(", start=${start.blockId}:${start.cellRow},${start.cellCol}/${start.innerRow},${start.innerCol}/h${start.heading}")
            }
            append(", targets=${targets.joinToString(",")}")
            append(", global=${selection.globalPlan}")
        }
        sendPreparedCommand(
            label = "开始覆盖任务",
            action = "start",
            params = mapOf("taskKind" to "coverage", "coverage" to coverage),
            paramsSummary = summary
        )
    }

    fun sendMissionCommand(label: String, action: String) {
        val missionId = missionState.value?.missionId?.takeIf { it.isNotBlank() }
        if (missionId == null) {
            rejectCommand(action, "当前没有可操作的任务")
            return
        }
        sendPreparedCommand(
            label,
            action,
            mapOf("targetMissionId" to missionId),
            "targetMissionId=$missionId"
        )
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
        if (_retryAvailable.value != true) {
            rejectCommand(command.cmd, "当前没有可重试的失败命令")
            return
        }
        if (waitingCmdId != null || mqttConnected.value != true || deviceOnline.value != true) {
            rejectCommand(command.cmd, "设备未就绪，暂时不能重试")
            return
        }
        pendingCommandParamsSummary = lastCommandParamsSummary
        _retryAvailable.value = false
        publishPreparedCommand(label, command, lastCommandParamsSummary)
    }

    private fun sendPreparedCommand(
        label: String,
        action: String,
        params: Any = emptyMap<String, Any?>(),
        paramsSummary: String = "{}"
    ) {
        if (!debounce()) {
            rejectCommand(action, "操作过于频繁，请稍后重试")
            return
        }
        if (!canSendCommand(action)) {
            rejectCommand(action, "MQTT 未连接、设备离线或命令不受支持")
            return
        }
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
        lastCommandParamsSummary = paramsSummary
        pendingCommandParamsSummary = paramsSummary
        _retryAvailable.value = false
        publishPreparedCommand(label, command, paramsSummary)
    }

    private fun publishPreparedCommand(
        label: String,
        command: PreparedCommand,
        paramsSummary: String?
    ) {
        waitingCmdId = command.cmdId
        waitingCommand = command
        _commandInFlight.value = true
        _commandState.value = CommandUiState(
            cmdId = command.cmdId,
            cmd = command.cmd,
            status = CommandStatus.SENDING,
            message = "$label 发送中",
            paramsSummary = paramsSummary
        )
        viewModelScope.launch(Dispatchers.IO) {
            val result = mqtt.publishCmd(command)
            if (result.published && result.cmdId != null) {
                viewModelScope.launch {
                    if (waitingCmdId == result.cmdId) {
                        commandTimeoutJob?.cancel()
                        commandTimeoutJob = viewModelScope.launch {
                            delay(5000)
                            if (waitingCmdId == result.cmdId) {
                                finishPendingCommand(CommandStatus.TIMEOUT, "$label 回执超时", null)
                            }
                        }
                    }
                }
                LogUtils.device("已发送操作：$label")
            } else {
                viewModelScope.launch {
                    if (waitingCmdId == command.cmdId) {
                        finishPendingCommand(CommandStatus.FAILED, "$label 发送失败", null)
                    }
                }
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
            val pendingCmd = waitingCommand?.cmd
            if (pendingCmd != null && ack.cmd != pendingCmd) {
                LogUtils.device(
                    "忽略命令类型不匹配的回执：cmdId=${ack.cmdId} " +
                        "expected=$pendingCmd actual=${ack.cmd}"
                )
                return
            }
            val status = if (ack.ackStatus == "success") CommandStatus.SUCCESS else CommandStatus.FAILED
            applyAckSideEffects(ack, status)
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
        } else if (
            pending == null &&
            ack.cmdId == lastPreparedCommand?.cmdId &&
            ack.cmd == lastPreparedCommand?.cmd
        ) {
            // ACK 可能在本地等待超时后到达。仍需关联到原命令，并以 Robot 的
            // 最终同步受理结果收敛重试入口，不能把成功 ACK 留成“可重试”。
            val status = if (ack.ackStatus == "success") CommandStatus.SUCCESS else CommandStatus.FAILED
            applyAckSideEffects(ack, status)
            _retryAvailable.postValue(status == CommandStatus.FAILED)
            _commandState.postValue(
                CommandUiState(
                    cmdId = ack.cmdId,
                    cmd = ack.cmd,
                    status = status,
                    errorCode = ack.errorCode,
                    paramsSummary = lastCommandParamsSummary
                )
            )
        } else {
            LogUtils.device("忽略无法关联的命令回执：cmdId=${ack.cmdId}")
        }
    }

    private fun applyAckSideEffects(ack: CmdAckMessage, status: CommandStatus) {
        if (ack.cmd == "manual") {
            _remoteModeAccepted.postValue(status == CommandStatus.SUCCESS)
        } else if (ack.cmd == "auto" && status == CommandStatus.SUCCESS) {
            _remoteModeAccepted.postValue(false)
        }
        if (ack.cmd == "start") {
            val mission = missionState.value
            val stateAlreadyUpdated = !mission?.missionId.isNullOrBlank() &&
                mission?.runState in setOf(
                    "starting",
                    "running",
                    "paused",
                    "succeeded",
                    "failed",
                    "canceled"
                )
            _awaitingStartStatus.postValue(
                status == CommandStatus.SUCCESS && !stateAlreadyUpdated
            )
        }
        if (ack.cmd == "clear_estop") {
            _awaitingClearEstopStatus.postValue(
                status == CommandStatus.SUCCESS &&
                    missionState.value?.safetyState != "normal"
            )
        }
    }

    private fun finishPendingCommand(status: CommandStatus, message: String?, errorCode: String?) {
        val cmdId = waitingCmdId
        val cmd = waitingCommand?.cmd
        waitingCmdId = null
        waitingCommand = null
        _commandInFlight.postValue(false)
        commandTimeoutJob?.cancel()
        _retryAvailable.postValue(
            status in setOf(
                CommandStatus.FAILED,
                CommandStatus.TIMEOUT,
                CommandStatus.CONNECTION_LOST
            )
        )
        _commandState.postValue(
            CommandUiState(
                cmdId = cmdId,
                cmd = cmd,
                status = status,
                message = message,
                errorCode = errorCode,
                paramsSummary = pendingCommandParamsSummary
            )
        )
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
        return MissionControlPolicy.compute(
            connected = connected,
            online = online,
            mission = missionState.value ?: MissionState(),
            manualCommandAccepted = _remoteModeAccepted.value == true,
            awaitingStartStatus = _awaitingStartStatus.value == true,
            awaitingClearEstopStatus = _awaitingClearEstopStatus.value == true,
            commandInFlight = _commandInFlight.value == true,
            retryAvailable = _retryAvailable.value == true
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
    val canManual: Boolean = false,
    val canAuto: Boolean = false,
    val canRemote: Boolean = false,
    val canRetry: Boolean = false
)
