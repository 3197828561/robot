package com.robot.solar.ui.main

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.robot.solar.BuildConfig
import com.robot.solar.databinding.ActivityMainBinding
import com.robot.solar.databinding.DialogCoverageTaskBinding
import com.robot.solar.entity.StructuredLogEntity
import com.robot.solar.map.MapPosition
import com.robot.solar.map.PvMapParser
import com.robot.solar.network.mqtt.CmdAckMessage
import com.robot.solar.network.mqtt.CommandStatus
import com.robot.solar.network.mqtt.CommandUiState
import com.robot.solar.network.mqtt.CoverageStart
import com.robot.solar.network.mqtt.CoverageTaskSelection
import com.robot.solar.network.mqtt.MapLoadStatus
import com.robot.solar.network.mqtt.MapUiState
import com.robot.solar.network.mqtt.PoseMessage
import com.robot.solar.network.mqtt.StatusMessage
import com.robot.solar.ui.common.ProtocolDisplayText
import com.robot.solar.ui.device.DeviceListActivity
import com.robot.solar.ui.log.LogActivity
import com.robot.solar.viewmodel.ControlAvailability
import com.robot.solar.viewmodel.MainViewModel
import com.robot.solar.viewmodel.ManualSpeedSettings
import com.robot.solar.viewmodel.MissionCommandErrorDisplay
import com.robot.solar.viewmodel.MissionStatusDisplay
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private val clockHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private var currentAvailability = ControlAvailability()
    private val mapParser = PvMapParser()
    private var currentMapState = MapUiState()
    private var currentPose: PoseMessage? = null
    private val poseTrail = ArrayDeque<Pair<Long, MapPosition>>()
    private val pendingAckDialogs = ArrayDeque<CmdAckMessage>()
    private var ackDialogShowing = false
    private var currentPage = Page.HOME

    private val clockRunnable = object : Runnable {
        override fun run() {
            binding.tvToolbarTime.text = timeFormat.format(Date())
            clockHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        binding.tvDeviceName.text = viewModel.deviceDisplayName ?: "--"
        binding.tvDeviceId.text = "设备编号：${viewModel.deviceId ?: "--"}"
        binding.tvProductType.text = "设备类型：${ProtocolDisplayText.productType(this, viewModel.productType)}"
        bindObservers()
        bindControls()
        binding.mapPreviewView.interactionEnabled = true
        binding.mapPreviewView.showLabels = true
        showPage(Page.HOME)
        bindCommandRows(emptyList())
    }

    override fun onStart() {
        super.onStart()
        viewModel.onScreenReady()
        clockHandler.post(clockRunnable)
    }

    override fun onPause() {
        binding.directionPad.cancelInput()
        viewModel.ordinaryRemoteStop()
        super.onPause()
    }

    override fun onStop() {
        clockHandler.removeCallbacks(clockRunnable)
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) viewModel.shutdownMqtt()
        super.onDestroy()
    }

    private fun bindObservers() {
        viewModel.mqttConnected.observe(this) { connected ->
            binding.tvMqttStatus.text = "MQTT：${if (connected) "已连接" else "未连接"}"
            bindStatus(viewModel.status.value)
        }
        viewModel.deviceOnline.observe(this) { online ->
            binding.tvDeviceOnline.text = "在线状态：${when (online) {
                true -> "在线"
                false -> "离线"
                null -> "--"
            }}"
            bindStatus(viewModel.status.value)
        }
        viewModel.lastHeartbeatAt.observe(this) { time ->
            binding.tvLastHeartbeat.text = "最后在线时间：${time?.let { timeFormat.format(Date(it)) } ?: "--"}"
            bindStatus(viewModel.status.value)
        }
        viewModel.status.observe(this) { bindStatus(it) }
        viewModel.missionState.observe(this) { bindStatus(viewModel.status.value) }
        viewModel.batteryPercent.observe(this) { binding.batteryIndicator.setBatteryPercent(it) }
        viewModel.mapState.observe(this) { bindMap(it) }
        viewModel.pose.observe(this) { bindPose(it) }
        viewModel.manualSpeedSettings.observe(this) {
            binding.manualSpeedControl.setSettings(it)
            bindStatus(viewModel.status.value)
        }
        viewModel.commandState.observe(this) { bindCommandState(it) }
        viewModel.commandAckEvent.observe(this) { event ->
            event.consume()?.let(::enqueueCommandAckDialog)
        }
        viewModel.recentCommandLogs.observe(this) { bindCommandRows(it.orEmpty()) }
        viewModel.controlsEnabled.observe(this) { bindAvailability(it) }
        viewModel.awaitingStartStatus.observe(this) { bindStatus(viewModel.status.value) }
        viewModel.awaitingClearEstopStatus.observe(this) { bindStatus(viewModel.status.value) }
    }

    private fun applySystemBarInsets() {
        val initialLeft = binding.root.paddingLeft
        val initialTop = binding.root.paddingTop
        val initialRight = binding.root.paddingRight
        val initialBottom = binding.root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left = initialLeft + bars.left,
                top = initialTop + bars.top,
                right = initialRight + bars.right,
                bottom = initialBottom + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun bindControls() {
        binding.manualSpeedControl.onSettingsChanged = viewModel::setManualSpeedSettings
        binding.btnStart.setOnClickListener { showCoverageTaskDialog() }
        binding.btnStopRun.setOnClickListener { viewModel.sendMissionCommand("停止任务", "stop") }
        binding.btnPause.setOnClickListener { viewModel.sendMissionCommand("暂停任务", "pause") }
        binding.btnResume.setOnClickListener { viewModel.sendMissionCommand("恢复任务", "resume") }
        binding.btnReplan.setOnClickListener { viewModel.sendMissionCommand("重新规划", "replan") }
        binding.btnEmergency.setOnClickListener { viewModel.sendCmd("紧急停止", "estop") }
        binding.btnClearEstop.setOnClickListener { viewModel.sendCmd("解除急停", "clear_estop") }
        binding.btnRemoteEmergency.setOnClickListener {
            binding.directionPad.cancelInput(notifyRelease = false)
            viewModel.stopRemote(sendZero = false)
            viewModel.sendCmd("紧急停止", "estop")
        }
        binding.btnRemoteStop.setOnClickListener {
            binding.directionPad.cancelInput()
            viewModel.ordinaryRemoteStop()
        }
        binding.btnEnterManualMode.setOnClickListener { viewModel.enterRemoteMode() }
        binding.btnReturnAutoMode.setOnClickListener { viewModel.exitRemoteMode() }
        binding.btnRetryCommand.setOnClickListener { viewModel.retryLastCommand() }
        binding.btnReloadMap.setOnClickListener { viewModel.retryMapDownload() }
        binding.btnCenterRobot.setOnClickListener {
            if (!binding.mapPreviewView.centerRobot()) {
                binding.mapPreviewView.resetViewport()
                Toast.makeText(this, "暂无有效机器人位置，已显示全部地图", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnMapReset.setOnClickListener { binding.mapPageView.resetViewport() }
        binding.btnMapCenter.setOnClickListener { centerMapOnRobot() }
        binding.btnMapZoomIn.setOnClickListener { binding.mapPageView.zoomIn() }
        binding.btnMapZoomOut.setOnClickListener { binding.mapPageView.zoomOut() }
        binding.btnMapLocate.setOnClickListener { centerMapOnRobot() }
        binding.btnViewLogs.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }

        binding.directionPad.listener = object : DirectionPadView.Listener {
            override fun onPress(direction: ManualDirection) {
                if (currentAvailability.canRemote) viewModel.startRemote(direction)
            }

            override fun onRelease() {
                viewModel.stopRemote(sendZero = true)
            }

            override fun onConflict() {
                viewModel.ordinaryRemoteStop()
                Toast.makeText(this@MainActivity, "请勿同时按多个方向按钮", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnDeviceList.setOnClickListener {
            viewModel.shutdownMqtt()
            startActivity(Intent(this, DeviceListActivity::class.java))
            finish()
        }
        binding.navHome.setOnClickListener { showPage(Page.HOME) }
        binding.navMap.setOnClickListener { showPage(Page.MAP) }
        binding.navRemote.setOnClickListener { showPage(Page.REMOTE) }
        binding.navStatus.setOnClickListener { showPage(Page.STATUS) }
    }

    private fun showCoverageTaskDialog() {
        val map = currentMapState.pvMap
        if (map == null) {
            Toast.makeText(this, "请先加载有效地图", Toast.LENGTH_SHORT).show()
            return
        }
        val dialogBinding = DialogCoverageTaskBinding.inflate(layoutInflater)
        dialogBinding.tvCoverageMap.text = "地图：${map.mapId}  版本：${map.version}"
        val targetBlocks = map.blocks
            .filter { it.cleanable && it.blockId > 0 }
            .sortedBy { it.blockId }
        var updatingTargetSelection = false
        targetBlocks.forEach { block ->
            dialogBinding.chipGroupTargetBlocks.addView(
                Chip(this).apply {
                    text = "区域 ${block.blockId}"
                    isCheckable = true
                    isChecked = true
                    tag = block.blockId
                    setOnCheckedChangeListener { _, _ ->
                        if (updatingTargetSelection) return@setOnCheckedChangeListener
                        val allChecked = (0 until dialogBinding.chipGroupTargetBlocks.childCount)
                            .map { dialogBinding.chipGroupTargetBlocks.getChildAt(it) as Chip }
                            .all(Chip::isChecked)
                        if (dialogBinding.cbSelectAllBlocks.isChecked != allChecked) {
                            updatingTargetSelection = true
                            dialogBinding.cbSelectAllBlocks.isChecked = allChecked
                            updatingTargetSelection = false
                        }
                    }
                }
            )
        }
        dialogBinding.cbSelectAllBlocks.setOnCheckedChangeListener { _, checked ->
            if (updatingTargetSelection) return@setOnCheckedChangeListener
            updatingTargetSelection = true
            (0 until dialogBinding.chipGroupTargetBlocks.childCount)
                .map { dialogBinding.chipGroupTargetBlocks.getChildAt(it) as Chip }
                .forEach { it.isChecked = checked }
            updatingTargetSelection = false
        }
        currentPose?.let { pose ->
            dialogBinding.etStartBlockId.setText(pose.blockId?.toString().orEmpty())
            dialogBinding.etStartCellRow.setText(pose.cellRow?.toString().orEmpty())
            dialogBinding.etStartCellCol.setText(pose.cellCol?.toString().orEmpty())
            dialogBinding.etStartInnerRow.setText(pose.innerRow?.toString().orEmpty())
            dialogBinding.etStartInnerCol.setText(pose.innerCol?.toString().orEmpty())
            dialogBinding.etStartHeading.setText(pose.headingCode?.toString().orEmpty())
        }
        fun updateStartFields() {
            dialogBinding.groupCoverageStart.visibility =
                if (dialogBinding.cbUseCurrentPose.isChecked) View.GONE else View.VISIBLE
        }
        dialogBinding.cbUseCurrentPose.setOnCheckedChangeListener { _, _ -> updateStartFields() }
        updateStartFields()

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("配置覆盖任务")
            .setView(dialogBinding.root)
            .setNegativeButton("取消", null)
            .setPositiveButton("开始任务", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val targets = (0 until dialogBinding.chipGroupTargetBlocks.childCount)
                    .map { dialogBinding.chipGroupTargetBlocks.getChildAt(it) as Chip }
                    .filter(Chip::isChecked)
                    .map { it.tag as Long }
                if (targets.isEmpty()) {
                    Toast.makeText(this, "至少选择一个目标区域", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val useCurrentPose = dialogBinding.cbUseCurrentPose.isChecked
                val start = if (useCurrentPose) {
                    null
                } else {
                    val blockId = dialogBinding.etStartBlockId.text?.toString()?.toLongOrNull()
                    val cellRow = dialogBinding.etStartCellRow.text?.toString()?.toIntOrNull()
                    val cellCol = dialogBinding.etStartCellCol.text?.toString()?.toIntOrNull()
                    val innerRow = dialogBinding.etStartInnerRow.text?.toString()?.toIntOrNull()
                    val innerCol = dialogBinding.etStartInnerCol.text?.toString()?.toIntOrNull()
                    val heading = dialogBinding.etStartHeading.text?.toString()?.toIntOrNull()
                    if (
                        blockId == null ||
                        cellRow == null ||
                        cellCol == null ||
                        innerRow == null ||
                        innerCol == null ||
                        heading == null
                    ) {
                        Toast.makeText(this, "请完整填写起点的六个字段", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    CoverageStart(
                        blockId = blockId,
                        cellRow = cellRow,
                        cellCol = cellCol,
                        innerRow = innerRow,
                        innerCol = innerCol,
                        heading = heading
                    )
                }
                viewModel.startCoverage(
                    CoverageTaskSelection(
                        useCurrentPose = useCurrentPose,
                        start = start,
                        targetBlockIds = targets,
                        globalPlan = dialogBinding.cbGlobalPlan.isChecked
                    )
                )
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun bindStatus(status: StatusMessage?) {
        val details = buildStatusDetails(status)
        val mission = viewModel.missionState.value
        val speed = viewModel.manualSpeedSettings.value ?: ManualSpeedSettings()
        val remoteDetails = listOf(
            "连接状态：MQTT ${if (viewModel.mqttConnected.value == true) "已连接" else "未连接"} · " +
                "机器人${when (viewModel.deviceOnline.value) {
                true -> "在线"
                false -> "离线"
                null -> "--"
            }}",
            "控制条件：模式 ${status?.operationalMode ?: mission?.operationalMode ?: "--"} · " +
                "安全 ${status?.safetyState ?: mission?.safetyState ?: "--"}",
            "手动控制：${manualControlStateText()} · 速度 ${speed.linearSpeedCms.toInt()} cm/s / " +
                String.format(Locale.getDefault(), "%.1f rad/s", speed.angularSpeedRadps)
        ).joinToString("\n")
        binding.tvStatusDetails.text = details
        binding.tvRemoteStatus.text = remoteDetails
        binding.tvRemoteModeState.text =
            "运行模式：${status?.operationalMode ?: mission?.operationalMode ?: "--"} · " +
                "安全状态：${status?.safetyState ?: mission?.safetyState ?: "--"}"
        bindHomeStatusCard(status)
    }

    /**
     * 状态详情只展示真实数据源：
     * - 设备基础信息来自设备列表 HTTP API；
     * - 机器人、任务字段来自 MQTT status；
     * - 地图与定位来自 MQTT map/pose（地图允许使用同一消息落盘的缓存）；
     * - APP 信息来自 BuildConfig，心跳时间为 APP 实际接收时间。
     */
    private fun buildStatusDetails(status: StatusMessage?): String = listOf(
        "【设备列表 API】",
        "设备名称 display_name：${viewModel.deviceDisplayName ?: "--"}",
        "设备编号 device_id：${viewModel.deviceId ?: "--"}",
        "设备类型 product_type：${viewModel.productType?.let {
            ProtocolDisplayText.productType(this, it)
        } ?: "--"}",
        "",
        "【APP 本地构建配置】",
        "APP版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        "任务接口能力：${BuildConfig.MISSION_COMMAND_API_CAPABILITY}",
        "",
        "【MQTT status】",
        "状态消息时间 timestamp：${status?.timestamp ?: "--"}",
        "工作状态 workStatus：${status?.let {
            ProtocolDisplayText.workStatus(this, it.workStatus)
        } ?: "--"}",
        "控制模式 controlMode：${status?.let {
            ProtocolDisplayText.controlMode(this, it.controlMode)
        } ?: "--"}",
        "电量 batteryPercent：${status?.batteryPercent?.let {
            "${it.toInt().coerceIn(0, 100)}%"
        } ?: "--"}",
        "线速度 linearSpeedCms：${status?.linearSpeedCms?.let {
            String.format(Locale.getDefault(), "%.1f cm/s", it)
        } ?: "--"}",
        "角速度 angularSpeedRadps：${status?.angularSpeedRadps?.let {
            String.format(Locale.getDefault(), "%.2f rad/s", it)
        } ?: "--"}",
        "设备状态 deviceStatus：${status?.let {
            ProtocolDisplayText.deviceStatus(this, it.deviceStatus)
        } ?: "--"}",
        "运动状态 movementStatus：${status?.let {
            ProtocolDisplayText.movementStatus(this, it.movementStatus)
        } ?: "--"}",
        "根任务编号 rootMissionId：${status?.rootMissionId ?: "--"}",
        "当前任务编号 missionId：${status?.missionId ?: "--"}",
        "当前任务类型 taskKind：${ProtocolDisplayText.taskKind(status?.taskKind)}",
        "整体任务状态 orchestrationState：${ProtocolDisplayText.orchestrationState(status?.orchestrationState)}",
        "任务栈深度 taskStackDepth：${status?.taskStackDepth ?: "--"}",
        "中断原因 interruptionReason：${ProtocolDisplayText.interruptionReason(status?.interruptionReason)}",
        "任务状态（安全状态优先，其次根任务状态）：${status?.let {
            MissionStatusDisplay.text(
                runState = it.runState,
                safetyState = it.safetyState,
                awaitingStart = viewModel.awaitingStartStatus.value == true,
                awaitingClearEstop = viewModel.awaitingClearEstopStatus.value == true,
                orchestrationState = it.orchestrationState,
                taskStackDepth = it.taskStackDepth,
                interruptionReason = it.interruptionReason
            )
        } ?: "--"}",
        "当前任务状态 runState：${status?.runState ?: "--"}",
        "运行模式 operationalMode：${status?.operationalMode ?: "--"}",
        "安全状态 safetyState：${status?.safetyState ?: "--"}",
        "任务阶段 phase：${status?.phase ?: "--"}",
        "当前动作 activeAction：${status?.activeAction?.takeIf { it.isNotBlank() } ?: "--"}",
        "航点索引 waypointIndex：${status?.waypointIndex ?: "--"}",
        "航点总数 waypointCount：${status?.waypointCount ?: "--"}",
        "错误码 errorCode：${status?.missionErrorCode ?: "--"}",
        "错误可重试 errorRetryable：${status?.errorRetryable ?: "--"}",
        "错误来源 errorSource：${status?.errorSource?.takeIf { it.isNotBlank() } ?: "--"}",
        "错误信息 errorMessage：${status?.errorMessage?.takeIf { it.isNotBlank() } ?: "--"}",
        "",
        "【MQTT map（允许本地缓存）/ pose】",
        "地图编号 mapId：${currentMapState.map?.mapId ?: "--"}",
        "地图版本 mapVersion：${currentMapState.map?.mapVersion ?: "--"}",
        "当前区域 blockId：${currentPose?.blockId ?: "--"}",
        "当前单元 cellId：${currentPose?.cellId ?: "--"}",
        "机器人朝向 heading：${
            currentPose?.let {
                ProtocolDisplayText.mapHeading(it.headingCode, it.heading)
            } ?: "--"
        }",
        "",
        "【MQTT heartbeat】",
        "APP最近收到心跳：${binding.tvLastHeartbeat.text.removePrefix("最后在线时间：")}"
    ).joinToString("\n")

    private fun bindMap(mapState: MapUiState) {
        currentMapState = mapState
        val stateText = when (mapState.status) {
            MapLoadStatus.NO_MAP -> "暂无地图"
            MapLoadStatus.DOWNLOADING -> "正在加载"
            MapLoadStatus.READY -> "地图已加载"
            MapLoadStatus.FAILED -> "地图加载失败"
        }
        binding.tvMapState.text = stateText
        binding.tvMapPageState.text = stateText
        val map = mapState.map
        val meta = if (map == null) {
            "--"
        } else {
            "地图：${map.mapName ?: "--"}  编号：${map.mapId ?: "--"}  版本：${map.mapVersion ?: "--"}"
        }
        binding.tvMapMeta.text = meta
        val readyMap = mapState.pvMap.takeIf { mapState.status == MapLoadStatus.READY }
        binding.tvMapPageMeta.text = readyMap?.let {
            "■ 光伏板区域（${it.cells.size}）"
        } ?: "■ 光伏板区域"
        binding.tvMapBridgeLegend.text = readyMap?.let {
            "■ 板间桥接区域（${it.bridges.size}）"
        } ?: "■ 板间桥接区域"
        binding.mapPreviewView.setMap(readyMap)
        binding.mapPageView.setMap(readyMap)
        binding.tvMapState.visibility = if (readyMap == null) View.VISIBLE else View.GONE
        binding.tvMapPageState.visibility = if (readyMap == null) View.VISIBLE else View.GONE
        poseTrail.clear()
        bindPose(currentPose)
    }

    private fun bindPose(pose: PoseMessage?) {
        currentPose = pose
        val map = currentMapState.pvMap ?: run {
            binding.mapPreviewView.setRobot(null, emptyList())
            binding.mapPageView.setRobot(null, emptyList())
            return
        }
        val position = pose?.let { mapParser.resolvePose(map, it) }
        val now = System.currentTimeMillis()
        if (position != null) poseTrail.addLast(now to position)
        while (poseTrail.firstOrNull()?.first?.let { now - it > 10_000 } == true) poseTrail.removeFirst()
        val history = poseTrail.map { it.second }
        binding.mapPreviewView.setRobot(position, history)
        binding.mapPageView.setRobot(position, history)
        bindStatus(viewModel.status.value)
    }

    private fun centerMapOnRobot() {
        if (!binding.mapPageView.centerRobot()) {
            Toast.makeText(this, "暂无有效机器人位置", Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindCommandState(state: CommandUiState) {
        val commandName = ProtocolDisplayText.commandName(this, state.cmd)
        val statusText = ProtocolDisplayText.commandStatus(this, state.status)
        binding.tvCommandState.text = "最近操作：$commandName · $statusText"
        if (
            state.status in setOf(CommandStatus.TIMEOUT, CommandStatus.CONNECTION_LOST) ||
            (state.status == CommandStatus.FAILED && !state.message.isNullOrBlank())
        ) {
            val feedback = ProtocolDisplayText.commandFeedback(this, state.cmd, state.status)
            val detail = MissionCommandErrorDisplay.text(state.errorCode) ?: state.message
            Toast.makeText(
                this,
                if (detail.isNullOrBlank()) feedback else "$feedback（$detail）",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun enqueueCommandAckDialog(ack: CmdAckMessage) {
        pendingAckDialogs.addLast(ack)
        showNextCommandAckDialog()
    }

    private fun showNextCommandAckDialog() {
        if (ackDialogShowing) return
        val ack = pendingAckDialogs.removeFirstOrNull() ?: return
        ackDialogShowing = true
        val success = ack.ackStatus == "success"
        val commandName = ProtocolDisplayText.commandName(this, ack.cmd)
        val detail = MissionCommandErrorDisplay.text(ack.errorCode)
            ?: ack.message?.takeIf { it.isNotBlank() }
            ?: if (success) "机器人已成功受理" else "机器人拒绝了该命令"
        MaterialAlertDialogBuilder(this)
            .setTitle(if (success) "$commandName：已受理" else "$commandName：失败")
            .setMessage(
                buildString {
                    append(detail)
                    if (!ack.cmdId.isNullOrBlank()) append("\n\n命令 ID：${ack.cmdId}")
                }
            )
            .setPositiveButton("知道了", null)
            .create()
            .apply {
                setOnDismissListener {
                    ackDialogShowing = false
                    showNextCommandAckDialog()
                }
                show()
            }
    }

    private fun bindHomeStatusCard(status: StatusMessage?) {
        val missionStatus = status?.let {
            MissionStatusDisplay.text(
                runState = it.runState,
                safetyState = it.safetyState,
                awaitingStart = viewModel.awaitingStartStatus.value == true,
                awaitingClearEstop = viewModel.awaitingClearEstopStatus.value == true,
                orchestrationState = it.orchestrationState,
                taskStackDepth = it.taskStackDepth,
                interruptionReason = it.interruptionReason
            )
        }
        val homeWorkStatus = missionStatus?.takeUnless { it == "--" }
            ?: status?.let { ProtocolDisplayText.workStatus(this, it.workStatus) }
            ?: "--"
        findViewById<TextView?>(com.robot.solar.R.id.tvHomeOnline)?.text =
            "在线状态：${when (viewModel.deviceOnline.value) {
                true -> "在线"
                false -> "离线"
                null -> "--"
            }}"
        findViewById<TextView?>(com.robot.solar.R.id.tvHomeWorkStatus)?.text =
            "工作状态：$homeWorkStatus"
        findViewById<TextView?>(com.robot.solar.R.id.tvHomeControlMode)?.text =
            "控制模式：${status?.let { ProtocolDisplayText.controlMode(this, it.controlMode) } ?: "--"}"
        findViewById<TextView?>(com.robot.solar.R.id.tvHomeBattery)?.text =
            "电量：${status?.batteryPercent?.let { "${it.toInt().coerceIn(0, 100)}%" } ?: "--"}"
        findViewById<TextView?>(com.robot.solar.R.id.tvHomeLinearSpeed)?.text =
            "线速度：${status?.linearSpeedCms?.let { String.format(Locale.getDefault(), "%.0f cm/s", it) } ?: "--"}"
        findViewById<TextView?>(com.robot.solar.R.id.tvHomeAngularSpeed)?.text =
            "角速度：${status?.angularSpeedRadps?.let { String.format(Locale.getDefault(), "%.2f rad/s", it) } ?: "--"}"
        findViewById<TextView?>(com.robot.solar.R.id.tvHomeDeviceStatus)?.text =
            "设备状态：${status?.let { ProtocolDisplayText.deviceStatus(this, it.deviceStatus) } ?: "--"}"
        findViewById<TextView?>(com.robot.solar.R.id.tvHomeMovementStatus)?.text =
            "运动状态：${status?.let { ProtocolDisplayText.movementStatus(this, it.movementStatus) } ?: "--"}"
    }

    private fun bindCommandRows(items: List<StructuredLogEntity>) {
        binding.commandHistoryTable.submitRows(
            items.take(4).map {
                val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it.timestampMillis))
                val params = runCatching {
                    JSONObject(it.detailJson.orEmpty()).optString("params").ifBlank { "--" }
                }.getOrDefault("--")
                CommandHistoryDisplayRow(
                    time = time,
                    command = ProtocolDisplayText.commandName(this, it.action),
                    params = params,
                    status = commandResultText(it.result),
                    description = it.summary
                )
            }
        )
    }

    private fun commandResultText(result: String?): String = when (result) {
        "idle" -> "待处理"
        "sending" -> "发送中"
        "success" -> "成功"
        "failed" -> "失败"
        "timeout" -> "超时"
        "connection_lost" -> "连接中断"
        "rejected" -> "已拒绝"
        null, "" -> "--"
        else -> result
    }

    private fun bindAvailability(availability: ControlAvailability) {
        currentAvailability = availability
        binding.btnStart.isEnabled = availability.canStart
        binding.btnStopRun.isEnabled = availability.canStop
        binding.btnPause.isEnabled = availability.canPause
        binding.btnResume.isEnabled = availability.canResume
        binding.btnReplan.isEnabled = availability.canReplan
        binding.btnEmergency.isEnabled = availability.canEstop
        binding.btnRemoteEmergency.isEnabled = availability.canEstop
        binding.btnClearEstop.isEnabled = availability.canClearEstop
        binding.btnEnterManualMode.isEnabled = availability.canManual
        binding.btnReturnAutoMode.isEnabled = availability.canAuto
        binding.btnRetryCommand.isEnabled = availability.canRetry
        binding.directionPad.controlsEnabled = availability.canRemote
        binding.btnRemoteStop.isEnabled = availability.canRemote
        binding.manualSpeedControl.setControlsEnabled(availability.canRemote)
        binding.tvRemoteHint.text = if (availability.canRemote) {
            "长按方向按钮 0.5 秒后开始，松开立即停止"
        } else {
            remoteUnavailableReason()
        }
        bindStatus(viewModel.status.value)
    }

    private fun showPage(page: Page) {
        if (currentPage == Page.REMOTE && page != Page.REMOTE) {
            binding.directionPad.cancelInput()
            viewModel.leaveRemotePage()
        }
        currentPage = page
        binding.sectionHome.visibility = if (page == Page.HOME) View.VISIBLE else View.GONE
        binding.sectionMap.visibility = if (page == Page.MAP) View.VISIBLE else View.GONE
        binding.sectionRemote.visibility = if (page == Page.REMOTE) View.VISIBLE else View.GONE
        binding.sectionStatus.visibility = if (page == Page.STATUS) View.VISIBLE else View.GONE
        selectNav(binding.navHome, page == Page.HOME)
        selectNav(binding.navMap, page == Page.MAP)
        selectNav(binding.navRemote, page == Page.REMOTE)
        selectNav(binding.navStatus, page == Page.STATUS)
    }

    private fun remoteUnavailableReason(): String {
        return when {
            viewModel.mqttConnected.value != true -> "MQTT 未连接，手动控制不可用"
            viewModel.deviceOnline.value != true -> "设备离线，手动控制不可用"
            viewModel.missionState.value?.safetyState != "normal" -> "安全状态不允许手动控制"
            viewModel.missionState.value?.operationalMode != "manual" -> "正在等待机器人切换到手动模式"
            else -> "当前条件不满足"
        }
    }

    private fun manualControlStateText(): String = when {
        currentAvailability.canRemote -> "可用"
        viewModel.mqttConnected.value != true -> "MQTT 未连接"
        viewModel.deviceOnline.value != true -> "设备离线"
        viewModel.missionState.value?.safetyState != "normal" -> "安全状态不允许"
        viewModel.missionState.value?.operationalMode != "manual" -> "未进入手动模式"
        else -> "等待机器人确认"
    }

    private fun selectNav(view: TextView, selected: Boolean) {
        view.setTextColor(getColor(if (selected) com.robot.solar.R.color.control_primary else com.robot.solar.R.color.control_nav_inactive))
        view.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }
}

private enum class Page {
    HOME,
    MAP,
    REMOTE,
    STATUS
}
