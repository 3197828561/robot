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
import com.robot.solar.map.MapPosition
import com.robot.solar.map.PvMapParser
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
import com.robot.solar.viewmodel.MissionCommandErrorDisplay
import com.robot.solar.viewmodel.MissionStatusDisplay
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private val commandHistory = ArrayDeque<HomeCommandRow>()
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
        bindCommandRows()
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
        }
        viewModel.commandState.observe(this) { bindCommandState(it) }
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
        dialogBinding.etTargetBlockIds.setText(
            map.blocks
                .filter { it.cleanable && it.blockId > 0 }
                .joinToString(",") { it.blockId.toString() }
        )
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
            .setPositiveButton("发送 START", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val rawTargets = dialogBinding.etTargetBlockIds.text?.toString().orEmpty().trim()
                val targets = runCatching {
                    rawTargets
                        .split(Regex("[,，\\s]+"))
                        .filter(String::isNotBlank)
                        .map(String::toLong)
                }.getOrElse {
                    Toast.makeText(this, "目标 blockId 格式错误", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (targets.isEmpty()) {
                    Toast.makeText(this, "至少填写一个目标 blockId", Toast.LENGTH_SHORT).show()
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
        val details = if (status == null) {
            listOf(
                "设备名称：${viewModel.deviceDisplayName ?: "--"}",
                "设备编号：${viewModel.deviceId ?: "--"}",
                "设备类型：${ProtocolDisplayText.productType(this, viewModel.productType)}",
                "APP版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                "任务接口能力：${BuildConfig.MISSION_COMMAND_API_CAPABILITY}",
                "工作状态：--",
                "控制模式：--",
                "电量：--",
                "线速度：--",
                "角速度：--",
                "设备状态：--",
                "运动状态：--",
                "任务编号：${viewModel.missionState.value?.missionId ?: "--"}",
                "任务类型：${viewModel.missionState.value?.taskKind ?: "--"}",
                "任务状态：${MissionStatusDisplay.text(
                    viewModel.missionState.value?.runState,
                    viewModel.missionState.value?.safetyState,
                    viewModel.awaitingStartStatus.value == true,
                    viewModel.awaitingClearEstopStatus.value == true
                )}",
                "runState：${viewModel.missionState.value?.runState ?: "--"}",
                "运行模式：${viewModel.missionState.value?.operationalMode ?: "--"}",
                "安全状态：${viewModel.missionState.value?.safetyState ?: "--"}",
                "任务阶段：${viewModel.missionState.value?.phase ?: "--"}",
                "当前动作：${viewModel.missionState.value?.activeAction ?: "--"}",
                "航点索引：${viewModel.missionState.value?.waypointIndex ?: "--"}",
                "航点总数：${viewModel.missionState.value?.waypointCount ?: "--"}",
                "错误码：${viewModel.missionState.value?.errorCode ?: "--"}",
                "错误可重试：${viewModel.missionState.value?.errorRetryable ?: "--"}",
                "错误来源：${viewModel.missionState.value?.errorSource?.takeIf { it.isNotBlank() } ?: "--"}",
                "错误信息：${viewModel.missionState.value?.errorMessage?.takeIf { it.isNotBlank() } ?: "--"}",
                "地图编号：${currentMapState.map?.mapId ?: "--"}",
                "地图版本：${currentMapState.map?.mapVersion ?: "--"}",
                "当前区域：${currentPose?.blockId ?: "--"}",
                "当前单元：${currentPose?.cellId ?: "--"}",
                "机器人朝向：${ProtocolDisplayText.mapHeading(currentPose?.headingCode, currentPose?.heading)}",
                "最后在线时间：${binding.tvLastHeartbeat.text.removePrefix("最后在线时间：")}"
            ).joinToString("\n")
        } else {
            listOf(
                "设备名称：${viewModel.deviceDisplayName ?: "--"}",
                "设备编号：${viewModel.deviceId ?: "--"}",
                "设备类型：${ProtocolDisplayText.productType(this, viewModel.productType)}",
                "APP版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                "任务接口能力：${BuildConfig.MISSION_COMMAND_API_CAPABILITY}",
                "工作状态：${ProtocolDisplayText.workStatus(this, status.workStatus)}",
                "控制模式：${ProtocolDisplayText.controlMode(this, status.controlMode)}",
                "电量：${status.batteryPercent?.let { "${it.toInt().coerceIn(0, 100)}%" } ?: "--"}",
                "线速度：${status.linearSpeedCms?.let { String.format(Locale.getDefault(), "%.1f cm/s", it) } ?: "--"}",
                "角速度：${status.angularSpeedRadps?.let { String.format(Locale.getDefault(), "%.2f rad/s", it) } ?: "--"}",
                "设备状态：${ProtocolDisplayText.deviceStatus(this, status.deviceStatus)}",
                "运动状态：${ProtocolDisplayText.movementStatus(this, status.movementStatus)}",
                "任务编号：${status.missionId ?: "--"}",
                "任务类型：${status.taskKind ?: "--"}",
                "任务状态：${MissionStatusDisplay.text(
                    status.runState,
                    status.safetyState,
                    viewModel.awaitingStartStatus.value == true,
                    viewModel.awaitingClearEstopStatus.value == true
                )}",
                "runState：${status.runState ?: "--"}",
                "运行模式：${status.operationalMode ?: "--"}",
                "安全状态：${status.safetyState ?: "--"}",
                "任务阶段：${status.phase ?: "--"}",
                "当前动作：${status.activeAction?.takeIf { it.isNotBlank() } ?: "--"}",
                "航点索引：${status.waypointIndex ?: "--"}",
                "航点总数：${status.waypointCount ?: "--"}",
                "错误码：${status.missionErrorCode ?: "--"}",
                "错误可重试：${status.errorRetryable ?: "--"}",
                "错误来源：${status.errorSource?.takeIf { it.isNotBlank() } ?: "--"}",
                "错误信息：${status.errorMessage?.takeIf { it.isNotBlank() } ?: "--"}",
                "地图编号：${currentMapState.map?.mapId ?: "--"}",
                "地图版本：${currentMapState.map?.mapVersion ?: "--"}",
                "当前区域：${currentPose?.blockId ?: "--"}",
                "当前单元：${currentPose?.cellId ?: "--"}",
                "机器人朝向：${ProtocolDisplayText.mapHeading(currentPose?.headingCode, currentPose?.heading)}",
                "最后在线时间：${binding.tvLastHeartbeat.text.removePrefix("最后在线时间：")}"
            ).joinToString("\n")
        }
        binding.tvStatusDetails.text = details
        binding.tvRemoteStatus.text = details
        val mission = viewModel.missionState.value
        binding.tvRemoteModeState.text =
            "运行模式：${status?.operationalMode ?: mission?.operationalMode ?: "--"} · " +
                "安全状态：${status?.safetyState ?: mission?.safetyState ?: "--"}"
        bindHomeStatusCard(status)
    }

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
        if (state.status != CommandStatus.IDLE || state.cmd != null) {
            upsertCommandRow(state, commandName, statusText)
            bindCommandRows()
        }
        if (state.status != CommandStatus.IDLE && state.status != CommandStatus.SENDING) {
            val feedback = ProtocolDisplayText.commandFeedback(this, state.cmd, state.status)
            val detail = MissionCommandErrorDisplay.text(state.errorCode) ?: state.message
            Toast.makeText(
                this,
                if (detail.isNullOrBlank()) feedback else "$feedback（$detail）",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun bindHomeStatusCard(status: StatusMessage?) {
        findViewById<TextView?>(com.robot.solar.R.id.tvHomeOnline)?.text =
            "在线状态：${when (viewModel.deviceOnline.value) {
                true -> "在线"
                false -> "离线"
                null -> "--"
            }}"
        findViewById<TextView?>(com.robot.solar.R.id.tvHomeWorkStatus)?.text =
            "工作状态：${status?.let { ProtocolDisplayText.workStatus(this, it.workStatus) } ?: "--"}"
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

    private fun upsertCommandRow(state: CommandUiState, commandName: String, statusText: String) {
        val key = state.cmdId ?: "${state.cmd}-${state.timestampMillis}"
        val row = HomeCommandRow(
            key = key,
            time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(state.timestampMillis)),
            command = state.cmd ?: commandName,
            params = state.paramsSummary ?: "--",
            status = statusText,
            description = commandDescription(state)
        )
        val existing = commandHistory.indexOfFirst { it.key == key }
        if (existing >= 0) {
            commandHistory.removeAt(existing)
        }
        commandHistory.addFirst(row)
        while (commandHistory.size > 4) commandHistory.removeLast()
    }

    private fun bindCommandRows() {
        val placeholders = listOf(
            com.robot.solar.R.id.tvCommandRow1,
            com.robot.solar.R.id.tvCommandRow2,
            com.robot.solar.R.id.tvCommandRow3,
            com.robot.solar.R.id.tvCommandRow4
        )
        placeholders.forEachIndexed { index, id ->
            findViewById<TextView?>(id)?.text = commandHistory.elementAtOrNull(index)?.let {
                "${it.time}    ${it.command}    ${it.params}    ${it.status}    ${it.description}"
            } ?: "--    --    --    --    --"
        }
    }

    private fun commandDescription(state: CommandUiState): String {
        val actionText = when (state.cmd) {
            "start" -> "覆盖任务已被任务层受理"
            "stop" -> "停止请求已被任务层受理"
            "pause" -> "暂停请求已被任务层受理"
            "resume" -> "恢复请求已被任务层受理"
            "replan" -> "重新规划请求已被任务层受理"
            "manual" -> "手动模式请求已被受理，等待状态确认"
            "auto" -> "自动模式请求已被受理，等待状态确认"
            "estop" -> "紧急停止执行"
            "clear_estop" -> "解除急停请求已受理，等待安全状态恢复"
            else -> "等待命令执行"
        }
        return when (state.status) {
            CommandStatus.SENDING -> "命令已发送，等待回执"
            CommandStatus.SUCCESS -> actionText
            CommandStatus.FAILED ->
                MissionCommandErrorDisplay.text(state.errorCode) ?: state.message ?: "设备未确认执行"
            CommandStatus.TIMEOUT -> "回执等待超时"
            CommandStatus.CONNECTION_LOST -> "连接中断，结果未知"
            CommandStatus.IDLE -> "--"
        }
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
        binding.tvRemoteHint.text = if (availability.canRemote) {
            "长按方向按钮 0.5 秒后开始，松开立即停止"
        } else {
            remoteUnavailableReason()
        }
    }

    private fun showPage(page: Page) {
        if (currentPage == Page.REMOTE && page != Page.REMOTE) {
            binding.directionPad.cancelInput()
            viewModel.exitRemoteMode()
        } else if (currentPage != Page.REMOTE && page == Page.REMOTE) {
            viewModel.enterRemoteMode()
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

private data class HomeCommandRow(
    val key: String,
    val time: String,
    val command: String,
    val params: String,
    val status: String,
    val description: String
)
