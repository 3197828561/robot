package com.robot.solar.viewmodel

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.robot.solar.BuildConfig
import com.robot.solar.data.session.ManualSpeedPreferences
import com.robot.solar.data.session.SessionManager
import com.robot.solar.entity.LogCategory
import com.robot.solar.entity.LogSeverity
import com.robot.solar.entity.LogSource
import com.robot.solar.entity.StructuredLogEntity
import com.robot.solar.entity.StructuredLogDraft
import com.robot.solar.map.MapRepository
import com.robot.solar.map.MapRepositoryState
import com.robot.solar.map.MapSyncManager
import com.robot.solar.map.MapSyncResult
import com.robot.solar.network.http.ApiClient
import com.robot.solar.network.mqtt.CloudCommMqttManager
import com.robot.solar.network.mqtt.CmdAckMessage
import com.robot.solar.network.mqtt.CommandPublishResult
import com.robot.solar.network.mqtt.CommandStatus
import com.robot.solar.network.mqtt.CommandUiState
import com.robot.solar.network.mqtt.CoverageCommandParams
import com.robot.solar.network.mqtt.CoverageTaskSelection
import com.robot.solar.network.mqtt.DeviceTopicIdentity
import com.robot.solar.network.mqtt.MissionState
import com.robot.solar.network.mqtt.PoseMessage
import com.robot.solar.network.mqtt.PreparedCommand
import com.robot.solar.network.mqtt.StatusMessage
import com.robot.solar.repository.DeviceRepository
import com.robot.solar.repository.LogRepository
import com.robot.solar.ui.main.ManualDirection
import com.robot.solar.utils.LogUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainViewModel internal constructor(
    application: Application,
    private val mqtt: MainMqttGateway,
    private val deviceIdentityProvider: MainDeviceIdentityProvider,
    private val mapRepository: MainMapRepository,
    recentCommandLogsFlow: Flow<List<StructuredLogEntity>>,
    private val manualSpeedStore: MainManualSpeedStore,
    private val mapSyncLauncher: (((suspend () -> Unit) -> Unit))? = null
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application = application,
        mqtt = CloudMqttGateway(CloudCommMqttManager.getInstance(application)),
        deviceIdentityProvider = DeviceRepositoryIdentityProvider(DeviceRepository.getInstance(application)),
        mapRepository = MainMapRepositoryAdapter(
            MapRepository(
                MapSyncManager(
                    cacheDir = application.cacheDir,
                    apiService = ApiClient.getService(SessionManager.getInstance(application))
                )
            )
        ),
        recentCommandLogsFlow = LogRepository.getInstance(application).observeRecentCommands(),
        manualSpeedStore = PreferencesManualSpeedStore(ManualSpeedPreferences(application))
    )

    private val manualSpeedDeviceId = deviceIdentityProvider.currentMqttIdentity().deviceId
    @Volatile
    private var manualSpeedSnapshot = manualSpeedStore.load(manualSpeedDeviceId)

    val mqttConnected: LiveData<Boolean> = mqtt.mqttConnected
    val deviceOnline: LiveData<Boolean?> = mqtt.deviceOnline
    val batteryPercent: LiveData<Int?> = mqtt.batteryPercent
    val status: LiveData<StatusMessage?> = mqtt.status
    val missionState: LiveData<MissionState> = mqtt.missionState
    val lastHeartbeatAt: LiveData<Long?> = mqtt.lastHeartbeatAt
    val httpMapV2State: LiveData<MapRepositoryState> = mapRepository.state.asLiveData()
    val pose: LiveData<PoseMessage?> = mqtt.pose
    private val _manualSpeedSettings = MutableLiveData(manualSpeedSnapshot)
    val manualSpeedSettings: LiveData<ManualSpeedSettings> = _manualSpeedSettings
    val recentCommandLogs = recentCommandLogsFlow.asLiveData()

    private val _commandState = MutableLiveData(CommandUiState(null, null, CommandStatus.IDLE))
    val commandState: LiveData<CommandUiState> = _commandState
    private val _commandAckEvent = MutableLiveData<OneShotEvent<CmdAckMessage>>()
    val commandAckEvent: LiveData<OneShotEvent<CmdAckMessage>> = _commandAckEvent
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
                stopRemote(sendZero = true, reason = "设备离线")
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
        get() = deviceIdentityProvider.currentDeviceName()
    val deviceId: String?
        get() = deviceIdentityProvider.currentDeviceId()
    val productType: String?
        get() = deviceIdentityProvider.currentProductType()

    private var lastCommandUptime: Long = 0L
    private val pendingCommands = linkedMapOf<String, PendingCommand>()
    private val issuedCommands = linkedMapOf<String, PendingCommand>()
    private var lastPreparedCommand: PreparedCommand? = null
    private var lastCommandLabel: String? = null
    private var lastCommandParamsSummary: String? = null
    private var pendingExitToAuto = false
    private var manualModeConfirmed = false
    private var remoteJob: Job? = null
    private var currentDirection: ManualDirection? = null
    @Volatile
    private var remoteSessionStarted = false
    private val cmdAckObserver = Observer<CmdAckMessage?> { handleCmdAck(it) }
    private val mqttConnectedObserver = Observer<Boolean> { connected ->
        if (connected != true) {
            _remoteModeAccepted.postValue(false)
            _awaitingStartStatus.postValue(false)
            _awaitingClearEstopStatus.postValue(false)
            stopRemote(sendZero = false, reason = "MQTT 连接中断")
            pendingCommands.keys.toList().forEach { cmdId ->
                finishPendingCommand(
                    cmdId,
                    CommandStatus.CONNECTION_LOST,
                    "MQTT 连接已断开",
                    null
                )
            }
        }
    }
    private val missionStateObserver = Observer<MissionState> { state ->
        if (hasStartedMissionState(state)) {
            _awaitingStartStatus.postValue(false)
        }
        if (state.safetyState == "normal") {
            _awaitingClearEstopStatus.postValue(false)
        }
        if (state.operationalMode == "manual" && _remoteModeAccepted.value == true) {
            manualModeConfirmed = true
        }
        if (state.operationalMode != "manual" || state.safetyState != "normal") {
            stopRemote(sendZero = true, reason = "运行模式或安全状态变化")
        }
        if (state.safetyState != "normal") {
            manualModeConfirmed = false
            _remoteModeAccepted.postValue(false)
        } else if (manualModeConfirmed && state.operationalMode != "manual") {
            manualModeConfirmed = false
            _remoteModeAccepted.postValue(false)
        }
        if (!BuildConfig.DEBUG_CONTROL_BYPASS) {
            if (state.operationalMode == "auto" && pendingExitToAuto) {
                _remoteModeAccepted.postValue(false)
                pendingExitToAuto = false
            } else if (
                pendingExitToAuto &&
                state.operationalMode == "manual" &&
                pendingCommands.isEmpty() &&
                mqttConnected.value == true &&
                deviceOnline.value == true
            ) {
                sendPreparedCommand("切回自动模式", "auto")
            }
        }
    }

    init {
        mqtt.lastCmdAck.observeForever(cmdAckObserver)
        mqtt.mqttConnected.observeForever(mqttConnectedObserver)
        mqtt.missionState.observeForever(missionStateObserver)
    }

    fun onScreenReady() {
        MainViewModelMapStartup.onScreenReady(
            deviceIdentityProvider = deviceIdentityProvider,
            mqtt = mqtt,
            mapRepository = mapRepository,
            mapSyncLauncher = mapSyncLauncher ?: { block ->
                viewModelScope.launch(Dispatchers.IO) { block() }
                Unit
            }
        )
    }

    fun startRemote(direction: ManualDirection) {
        if (!isRemoteAllowed(mqttConnected.value == true, deviceOnline.value == true)) return
        if (remoteJob?.isActive == true && currentDirection == direction) return
        stopRemote(sendZero = remoteJob?.isActive == true, reason = "切换控制方向")
        currentDirection = direction
        remoteJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                delay(500)
                if (!isRemoteAllowed(mqttConnected.value == true, deviceOnline.value == true)) {
                    return@launch
                }
                val initialDirection = currentDirection ?: return@launch
                val initialVelocity = ManualSpeedPolicy.velocityFor(initialDirection, manualSpeedSnapshot)
                remoteSessionStarted = true
                LogUtils.remote(
                    eventType = "remote_started",
                    summary = "开始${directionText(initialDirection)}控制",
                    result = "active",
                    detailJson = remoteDetail(
                        initialDirection,
                        initialVelocity.linearSpeedCms,
                        initialVelocity.angularSpeedRadps
                    )
                )
                while (true) {
                    if (!isRemoteAllowed(mqttConnected.value == true, deviceOnline.value == true)) break
                    val active = currentDirection ?: break
                    val velocity = ManualSpeedPolicy.velocityFor(active, manualSpeedSnapshot)
                    mqtt.publishRemote(velocity.linearSpeedCms, velocity.angularSpeedRadps)
                    delay(50)
                }
            } finally {
                if (remoteSessionStarted) {
                    remoteSessionStarted = false
                    mqtt.publishRemote(0.0, 0.0)
                    LogUtils.remote(
                        eventType = "remote_stopped",
                        summary = "控制条件变化，手动遥控已停止",
                        result = "stopped",
                        severity = LogSeverity.WARNING
                    )
                }
            }
        }
    }

    fun stopRemote(sendZero: Boolean = true, reason: String = "松开方向键") {
        val wasActive = remoteJob?.isActive == true
        val wasStarted = remoteSessionStarted
        val direction = currentDirection
        remoteSessionStarted = false
        remoteJob?.cancel()
        remoteJob = null
        currentDirection = null
        if (sendZero && wasActive && mqttConnected.value == true) {
            viewModelScope.launch(Dispatchers.IO) { mqtt.publishRemote(0.0, 0.0) }
        }
        if (wasStarted) {
            LogUtils.remote(
                eventType = "remote_stopped",
                summary = "${direction?.let(::directionText).orEmpty()}控制已停止：$reason",
                result = "stopped",
                detailJson = JSONObject().put("reason", reason).toString()
            )
        }
    }

    fun ordinaryRemoteStop() {
        val hadActiveControl = remoteJob?.isActive == true
        stopRemote(sendZero = false, reason = "用户执行普通停止")
        if (mqttConnected.value == true && (hadActiveControl || deviceOnline.value == true)) {
            viewModelScope.launch(Dispatchers.IO) { mqtt.publishRemote(0.0, 0.0) }
        }
    }

    fun leaveRemotePage() {
        val hadActiveControl = remoteJob?.isActive == true
        stopRemote(sendZero = false, reason = "离开手动控制页面")
        if (hadActiveControl && mqttConnected.value == true) {
            viewModelScope.launch(Dispatchers.IO) { mqtt.publishRemote(0.0, 0.0) }
        }
    }

    fun startCoverage(selection: CoverageTaskSelection) {
        val map = httpMapV2State.value?.currentResult?.pvMap
        if (map == null) {
            rejectCommand("start", "请先加载有效地图")
            return
        }
        if (map.mapId !in 1..UINT32_MAX || map.version !in 0..UINT32_MAX) {
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

    fun setManualSpeedSettings(settings: ManualSpeedSettings) {
        val normalized = ManualSpeedPolicy.normalize(settings)
        if (normalized == manualSpeedSnapshot) return
        manualSpeedSnapshot = normalized
        manualSpeedStore.save(manualSpeedDeviceId, normalized)
        _manualSpeedSettings.value = normalized
        if (remoteSessionStarted) {
            LogUtils.remote(
                eventType = "remote_speed_changed",
                summary = "手动控制速度已调整",
                result = "active",
                detailJson = JSONObject()
                    .put("linearSpeedCms", normalized.linearSpeedCms)
                    .put("angularSpeedRadps", normalized.angularSpeedRadps)
                    .toString()
            )
        }
    }

    fun selectManualSpeedPreset(preset: ManualSpeedPreset) {
        setManualSpeedSettings(ManualSpeedPolicy.fromPreset(preset))
    }

    fun adjustLinearSpeed(deltaCms: Double) {
        setManualSpeedSettings(
            manualSpeedSnapshot.copy(
                linearSpeedCms = manualSpeedSnapshot.linearSpeedCms + deltaCms
            )
        )
    }

    fun adjustAngularSpeed(deltaRadps: Double) {
        setManualSpeedSettings(
            manualSpeedSnapshot.copy(
                angularSpeedRadps = manualSpeedSnapshot.angularSpeedRadps + deltaRadps
            )
        )
    }

    fun sendMissionCommand(label: String, action: String) {
        val missionId = missionState.value?.controlMissionId
        if (missionId == null && !BuildConfig.DEBUG_CONTROL_BYPASS) {
            rejectCommand(action, "当前没有可操作的任务")
            return
        }
        val targetMissionId = missionId ?: DEBUG_MISSING_MISSION_ID
        sendPreparedCommand(
            label,
            action,
            mapOf("targetMissionId" to targetMissionId),
            "targetMissionId=$targetMissionId"
        )
    }

    fun sendCmd(label: String, action: String) {
        sendPreparedCommand(label, action)
    }

    fun enterRemoteMode() {
        pendingExitToAuto = false
        manualModeConfirmed = false
        _remoteModeAccepted.value = false
        if (
            !BuildConfig.DEBUG_CONTROL_BYPASS &&
            missionState.value?.safetyState != "normal"
        ) {
            rejectCommand("manual", "当前安全状态不允许进入手动模式")
            return
        }
        sendPreparedCommand("切换手动模式", "manual")
    }

    fun exitRemoteMode() {
        ordinaryRemoteStop()
        manualModeConfirmed = false
        _remoteModeAccepted.value = false
        pendingExitToAuto = true
        if (
            BuildConfig.DEBUG_CONTROL_BYPASS &&
            mqttConnected.value == true &&
            deviceOnline.value == true
        ) {
            sendPreparedCommand("切回自动模式", "auto")
            return
        }
        if (
            missionState.value?.operationalMode == "manual" &&
            pendingCommands.isEmpty() &&
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
        if (pendingCommands.isNotEmpty() || mqttConnected.value != true || deviceOnline.value != true) {
            rejectCommand(command.cmd, "设备未就绪，暂时不能重试")
            return
        }
        _retryAvailable.value = false
        publishPreparedCommand(label, command, lastCommandParamsSummary)
    }

    private fun sendPreparedCommand(
        label: String,
        action: String,
        params: Any = emptyMap<String, Any?>(),
        paramsSummary: String = "{}"
    ) {
        if (!BuildConfig.DEBUG_CONTROL_BYPASS && !debounce()) {
            rejectCommand(action, "操作过于频繁，请稍后重试")
            return
        }
        if (!canSendCommand(action)) {
            rejectCommand(action, "MQTT 未连接、设备离线或命令不受支持")
            return
        }
        if (!BuildConfig.DEBUG_CONTROL_BYPASS && pendingCommands.isNotEmpty()) {
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
        _retryAvailable.value = false
        publishPreparedCommand(label, command, paramsSummary)
    }

    private fun publishPreparedCommand(
        label: String,
        command: PreparedCommand,
        paramsSummary: String?
    ) {
        val pending = PendingCommand(command, label, paramsSummary)
        pendingCommands[command.cmdId] = pending
        issuedCommands[command.cmdId] = pending
        while (issuedCommands.size > MAX_ISSUED_COMMAND_HISTORY) {
            issuedCommands.remove(issuedCommands.keys.first())
        }
        _commandInFlight.value = true
        _commandState.value = CommandUiState(
            cmdId = command.cmdId,
            cmd = command.cmd,
            status = CommandStatus.SENDING,
            message = "$label 发送中",
            paramsSummary = paramsSummary
        )
        recordCommandState(
            cmdId = command.cmdId,
            action = command.cmd,
            label = label,
            status = CommandStatus.SENDING,
            message = "正在发布到 MQTT",
            paramsSummary = paramsSummary
        )
        viewModelScope.launch(Dispatchers.IO) {
            val result = mqtt.publishCmd(command)
            if (result.published && result.cmdId != null) {
                viewModelScope.launch {
                    pendingCommands[result.cmdId]?.let { active ->
                        active.timeoutJob?.cancel()
                        active.timeoutJob = viewModelScope.launch {
                            delay(5000)
                            if (pendingCommands.containsKey(result.cmdId)) {
                                finishPendingCommand(
                                    result.cmdId,
                                    CommandStatus.TIMEOUT,
                                    "$label 回执超时",
                                    null
                                )
                            }
                        }
                    }
                }
                recordCommandState(
                    cmdId = command.cmdId,
                    action = command.cmd,
                    label = label,
                    status = CommandStatus.SENDING,
                    message = "已发布，等待机器人回执",
                    paramsSummary = paramsSummary
                )
            } else {
                viewModelScope.launch {
                    if (pendingCommands.containsKey(command.cmdId)) {
                        finishPendingCommand(
                            command.cmdId,
                            CommandStatus.FAILED,
                            "$label 发送失败",
                            null
                        )
                    }
                }
            }
        }
    }

    private fun rejectCommand(action: String, message: String) {
        _commandState.postValue(CommandUiState(null, action, CommandStatus.FAILED, message))
        LogUtils.record(
            StructuredLogDraft(
                source = LogSource.APP,
                category = LogCategory.COMMAND,
                eventType = "command_rejected",
                severity = LogSeverity.WARNING,
                action = action,
                result = "rejected",
                summary = message,
                detailJson = JSONObject().put("action", action).toString()
            )
        )
    }

    private fun handleCmdAck(ack: CmdAckMessage?) {
        ack ?: return
        val ackCmdId = ack.cmdId
        val pending = ackCmdId?.let(pendingCommands::get)
        if (pending != null) {
            if (ack.cmd != pending.command.cmd) {
                LogUtils.record(
                    StructuredLogDraft(
                        source = LogSource.ROBOT,
                        category = LogCategory.COMMAND,
                        eventType = "ack_type_mismatch",
                        severity = LogSeverity.WARNING,
                        cmdId = ack.cmdId,
                        action = ack.cmd,
                        result = "ignored",
                        summary = "忽略命令类型不匹配的回执",
                        detailJson = JSONObject()
                            .put("expected", pending.command.cmd)
                            .put("actual", ack.cmd)
                            .toString()
                    )
                )
                return
            }
            val status = if (ack.ackStatus == "success") CommandStatus.SUCCESS else CommandStatus.FAILED
            applyAckSideEffects(ack, status)
            _commandAckEvent.postValue(OneShotEvent(ack))
            finishPendingCommand(ackCmdId, status, null, ack.errorCode)
            if (ack.cmd == "manual" && status == CommandStatus.SUCCESS && pendingExitToAuto) {
                viewModelScope.launch {
                    delay(400)
                    if (
                        pendingExitToAuto &&
                        missionState.value?.operationalMode == "manual" &&
                        pendingCommands.isEmpty()
                    ) {
                        sendPreparedCommand("切回自动模式", "auto")
                    }
                }
            }
        } else {
            val issued = ackCmdId?.let(issuedCommands::get)
            if (issued != null && ack.cmd == issued.command.cmd) {
                // ACK 可能在本地等待超时后到达。仍需关联到原命令，并以 Robot 的
                // 最终同步受理结果收敛重试入口，不能把成功 ACK 留成“可重试”。
                val status =
                    if (ack.ackStatus == "success") CommandStatus.SUCCESS else CommandStatus.FAILED
                applyAckSideEffects(ack, status)
                _commandAckEvent.postValue(OneShotEvent(ack))
                if (ack.cmdId == lastPreparedCommand?.cmdId) {
                    _retryAvailable.postValue(status == CommandStatus.FAILED)
                }
                _commandState.postValue(
                    CommandUiState(
                        cmdId = ack.cmdId,
                        cmd = ack.cmd,
                        status = status,
                        errorCode = ack.errorCode,
                        paramsSummary = issued.paramsSummary
                    )
                )
                recordCommandState(
                    cmdId = ack.cmdId,
                    action = ack.cmd,
                    label = issued.label,
                    status = status,
                    message = ack.message,
                    errorCode = ack.errorCode,
                    paramsSummary = issued.paramsSummary
                )
            } else {
                LogUtils.record(
                    StructuredLogDraft(
                        source = LogSource.ROBOT,
                        category = LogCategory.COMMAND,
                        eventType = "unmatched_ack",
                        severity = LogSeverity.WARNING,
                        cmdId = ack.cmdId,
                        action = ack.cmd,
                        result = "ignored",
                        summary = "忽略无法关联的命令回执",
                        detailJson = JSONObject()
                            .put("ackStatus", ack.ackStatus)
                            .put("errorCode", ack.errorCode)
                            .toString()
                    )
                )
            }
        }
    }

    private fun applyAckSideEffects(ack: CmdAckMessage, status: CommandStatus) {
        if (ack.cmd == "manual") {
            manualModeConfirmed = false
            _remoteModeAccepted.postValue(status == CommandStatus.SUCCESS)
        } else if (ack.cmd == "auto" && status == CommandStatus.SUCCESS) {
            manualModeConfirmed = false
            _remoteModeAccepted.postValue(false)
        }
        if (ack.cmd == "start") {
            val mission = missionState.value
            val stateAlreadyUpdated = mission?.let(::hasStartedMissionState) == true
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

    private fun finishPendingCommand(
        cmdId: String,
        status: CommandStatus,
        message: String?,
        errorCode: String?
    ) {
        val pending = pendingCommands.remove(cmdId) ?: return
        pending.timeoutJob?.cancel()
        _commandInFlight.postValue(pendingCommands.isNotEmpty())
        if (cmdId == lastPreparedCommand?.cmdId) {
            _retryAvailable.postValue(
                status in setOf(
                    CommandStatus.FAILED,
                    CommandStatus.TIMEOUT,
                    CommandStatus.CONNECTION_LOST
                )
            )
        }
        _commandState.postValue(
            CommandUiState(
                cmdId = cmdId,
                cmd = pending.command.cmd,
                status = status,
                message = message,
                errorCode = errorCode,
                paramsSummary = pending.paramsSummary
            )
        )
        recordCommandState(
            cmdId = cmdId,
            action = pending.command.cmd,
            label = pending.label,
            status = status,
            message = message,
            errorCode = errorCode,
            paramsSummary = pending.paramsSummary
        )
    }

    private fun recordCommandState(
        cmdId: String?,
        action: String?,
        label: String,
        status: CommandStatus,
        message: String?,
        errorCode: String? = null,
        paramsSummary: String? = null
    ) {
        val stableCmdId = cmdId ?: return
        val result = when (status) {
            CommandStatus.IDLE -> "idle"
            CommandStatus.SENDING -> "sending"
            CommandStatus.SUCCESS -> "success"
            CommandStatus.FAILED -> "failed"
            CommandStatus.TIMEOUT -> "timeout"
            CommandStatus.CONNECTION_LOST -> "connection_lost"
        }
        val severity = when (status) {
            CommandStatus.FAILED, CommandStatus.TIMEOUT -> LogSeverity.ERROR
            CommandStatus.CONNECTION_LOST -> LogSeverity.WARNING
            else -> LogSeverity.INFO
        }
        val detail = JSONObject()
            .put("params", paramsSummary)
            .put("message", message)
            .put("errorCode", errorCode)
            .put("result", result)
            .toString()
        LogUtils.command(
            cmdId = stableCmdId,
            action = action,
            summary = "$label：${message ?: result}",
            result = result,
            paramsSummary = paramsSummary,
            missionId = missionState.value?.missionId,
            severity = severity,
            detailJson = detail
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
        pendingCommands.values.forEach { it.timeoutJob?.cancel() }
        pendingCommands.clear()
        mqtt.lastCmdAck.removeObserver(cmdAckObserver)
        mqtt.mqttConnected.removeObserver(mqttConnectedObserver)
        mqtt.missionState.removeObserver(missionStateObserver)
        super.onCleared()
    }

    fun shutdownMqtt() {
        stopRemote(sendZero = true)
        pendingCommands.values.forEach { it.timeoutJob?.cancel() }
        pendingCommands.clear()
        _commandInFlight.value = false
        mqtt.shutdown()
    }

    private fun directionText(direction: ManualDirection): String = when (direction) {
        ManualDirection.FORWARD -> "前进"
        ManualDirection.BACKWARD -> "后退"
        ManualDirection.LEFT -> "左转"
        ManualDirection.RIGHT -> "右转"
    }

    private fun remoteDetail(
        direction: ManualDirection,
        linearSpeedCms: Double,
        angularSpeedRadps: Double
    ): String = JSONObject()
        .put("direction", direction.name.lowercase())
        .put("linearSpeedCms", linearSpeedCms)
        .put("angularSpeedRadps", angularSpeedRadps)
        .toString()

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

    private fun hasStartedMissionState(state: MissionState): Boolean {
        val v4StateReceived = !state.rootMissionId.isNullOrBlank() &&
            state.orchestrationState in setOf(
                "running",
                "paused_by_user",
                "paused_by_safety",
                "running_child",
                "resuming",
                "succeeded",
                "failed",
                "canceled"
            )
        val legacyStateReceived = state.rootMissionId.isNullOrBlank() &&
            !state.missionId.isNullOrBlank() &&
            state.runState in setOf("starting", "running", "paused", "succeeded", "failed", "canceled")
        return v4StateReceived || legacyStateReceived
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
            retryAvailable = _retryAvailable.value == true,
            debugBypass = BuildConfig.DEBUG_CONTROL_BYPASS
        )
    }

    companion object {
        private const val UINT32_MAX = 4_294_967_295L
        private const val MAX_ISSUED_COMMAND_HISTORY = 50
        private const val DEBUG_MISSING_MISSION_ID = "debug-no-active-mission"
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

class OneShotEvent<T>(private val value: T) {
    private var consumed = false

    @Synchronized
    fun consume(): T? {
        if (consumed) return null
        consumed = true
        return value
    }
}

private data class PendingCommand(
    val command: PreparedCommand,
    val label: String,
    val paramsSummary: String?,
    var timeoutJob: Job? = null
)

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

internal interface MainMqttGateway {
    val mqttConnected: LiveData<Boolean>
    val deviceOnline: LiveData<Boolean?>
    val batteryPercent: LiveData<Int?>
    val status: LiveData<StatusMessage?>
    val missionState: LiveData<MissionState>
    val lastHeartbeatAt: LiveData<Long?>
    val pose: LiveData<PoseMessage?>
    val lastCmdAck: LiveData<CmdAckMessage?>

    fun start(deviceId: String, productType: String)
    fun publishRemote(linearSpeedCms: Double, angularRadps: Double): Boolean
    fun prepareCommand(action: String, params: Any = emptyMap<String, Any?>()): PreparedCommand?
    fun publishCmd(command: PreparedCommand): CommandPublishResult
    fun shutdown()
}

private class CloudMqttGateway(
    private val mqtt: CloudCommMqttManager
) : MainMqttGateway {
    override val mqttConnected: LiveData<Boolean> = mqtt.mqttConnected
    override val deviceOnline: LiveData<Boolean?> = mqtt.deviceOnline
    override val batteryPercent: LiveData<Int?> = mqtt.batteryPercent
    override val status: LiveData<StatusMessage?> = mqtt.status
    override val missionState: LiveData<MissionState> = mqtt.missionState
    override val lastHeartbeatAt: LiveData<Long?> = mqtt.lastHeartbeatAt
    override val pose: LiveData<PoseMessage?> = mqtt.pose
    override val lastCmdAck: LiveData<CmdAckMessage?> = mqtt.lastCmdAck

    override fun start(deviceId: String, productType: String) = mqtt.start(deviceId, productType)
    override fun publishRemote(linearSpeedCms: Double, angularRadps: Double): Boolean =
        mqtt.publishRemote(linearSpeedCms, angularRadps)

    override fun prepareCommand(action: String, params: Any): PreparedCommand? =
        mqtt.prepareCommand(action, params)

    override fun publishCmd(command: PreparedCommand): CommandPublishResult = mqtt.publishCmd(command)
    override fun shutdown() = mqtt.shutdown()
}

internal interface MainDeviceIdentityProvider {
    fun currentDeviceName(): String?
    fun currentDeviceId(): String?
    fun currentProductType(): String?
    fun currentMqttIdentity(): DeviceTopicIdentity
}

private class DeviceRepositoryIdentityProvider(
    private val repository: DeviceRepository
) : MainDeviceIdentityProvider {
    override fun currentDeviceName(): String? = repository.currentDeviceName()
    override fun currentDeviceId(): String? = repository.currentDeviceId()
    override fun currentProductType(): String? = repository.currentProductType()
    override fun currentMqttIdentity(): DeviceTopicIdentity = repository.currentMqttIdentity()
}

internal interface MainMapRepository {
    val state: StateFlow<MapRepositoryState>
    suspend fun syncCurrentMap(productType: String, deviceId: String, force: Boolean = false): MapSyncResult
}

private class MainMapRepositoryAdapter(
    private val repository: MapRepository
) : MainMapRepository {
    override val state: StateFlow<MapRepositoryState> = repository.state
    override suspend fun syncCurrentMap(productType: String, deviceId: String, force: Boolean): MapSyncResult =
        repository.syncCurrentMap(productType, deviceId, force)
}

internal interface MainManualSpeedStore {
    fun load(deviceId: String): ManualSpeedSettings
    fun save(deviceId: String, settings: ManualSpeedSettings)
}

private class PreferencesManualSpeedStore(
    private val preferences: ManualSpeedPreferences
) : MainManualSpeedStore {
    override fun load(deviceId: String): ManualSpeedSettings = preferences.load(deviceId)
    override fun save(deviceId: String, settings: ManualSpeedSettings) = preferences.save(deviceId, settings)
}

internal object MainViewModelMapStartup {
    fun onScreenReady(
        deviceIdentityProvider: MainDeviceIdentityProvider,
        mqtt: MainMqttGateway,
        mapRepository: MainMapRepository,
        mapSyncLauncher: (suspend () -> Unit) -> Unit
    ) {
        val identity = deviceIdentityProvider.currentMqttIdentity()
        mqtt.start(identity.deviceId, identity.productType)
        mapSyncLauncher {
            runCatching {
                mapRepository.syncCurrentMap(
                    productType = identity.productType,
                    deviceId = identity.deviceId
                )
            }
        }
    }
}
