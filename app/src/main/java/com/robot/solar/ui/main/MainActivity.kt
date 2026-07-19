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
import com.robot.solar.databinding.ActivityMainBinding
import com.robot.solar.map.MapPosition
import com.robot.solar.map.PvMapParser
import com.robot.solar.network.mqtt.CommandStatus
import com.robot.solar.network.mqtt.CommandUiState
import com.robot.solar.network.mqtt.MapLoadStatus
import com.robot.solar.network.mqtt.MapMessage
import com.robot.solar.network.mqtt.MapUiState
import com.robot.solar.network.mqtt.PoseMessage
import com.robot.solar.network.mqtt.StatusMessage
import com.robot.solar.ui.common.ProtocolDisplayText
import com.robot.solar.ui.device.DeviceListActivity
import com.robot.solar.ui.log.LogActivity
import com.robot.solar.viewmodel.ControlAvailability
import com.robot.solar.viewmodel.MainViewModel
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
        binding.mapPreviewView.interactionEnabled = false
        binding.mapPreviewView.showLabels = false
        showPage(Page.HOME)
    }

    override fun onStart() {
        super.onStart()
        viewModel.onScreenReady()
        clockHandler.post(clockRunnable)
    }

    override fun onPause() {
        binding.directionPad.cancelInput()
        viewModel.stopRemote(sendZero = true)
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
        }
        viewModel.lastHeartbeatAt.observe(this) { time ->
            binding.tvLastHeartbeat.text = "最后在线时间：${time?.let { timeFormat.format(Date(it)) } ?: "--"}"
        }
        viewModel.status.observe(this) { bindStatus(it) }
        viewModel.batteryPercent.observe(this) { binding.batteryIndicator.setBatteryPercent(it) }
        viewModel.mapState.observe(this) { bindMap(it) }
        viewModel.pose.observe(this) { bindPose(it) }
        viewModel.commandState.observe(this) { bindCommandState(it) }
        viewModel.controlsEnabled.observe(this) { bindAvailability(it) }
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
        binding.btnStart.setOnClickListener { viewModel.sendCmd("开始运行", "start") }
        binding.btnStopRun.setOnClickListener { viewModel.sendCmd("停止运行", "stop") }
        binding.btnEmergency.setOnClickListener { viewModel.sendCmd("紧急停止", "estop") }
        binding.btnClearEstop.setOnClickListener { viewModel.sendCmd("解除急停", "clear_estop") }
        binding.btnRemoteEmergency.setOnClickListener {
            binding.directionPad.cancelInput()
            viewModel.ordinaryRemoteStop()
            viewModel.sendCmd("紧急停止", "estop")
        }
        binding.btnRemoteStop.setOnClickListener {
            binding.directionPad.cancelInput()
            viewModel.ordinaryRemoteStop()
        }
        binding.btnReloadMap.setOnClickListener { viewModel.retryMapDownload() }
        binding.btnCenterRobot.setOnClickListener {
            if (!binding.mapPageView.centerRobot()) {
                binding.mapPageView.resetViewport()
                Toast.makeText(this, "暂无有效机器人位置，已显示全部地图", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnMapReset.setOnClickListener { binding.mapPageView.resetViewport() }
        binding.btnMapCenter.setOnClickListener {
            if (!binding.mapPageView.centerRobot()) {
                Toast.makeText(this, "暂无有效机器人位置", Toast.LENGTH_SHORT).show()
            }
        }
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

    private fun bindStatus(status: StatusMessage?) {
        val details = if (status == null) {
            listOf(
                "设备名称：${viewModel.deviceDisplayName ?: "--"}",
                "设备编号：${viewModel.deviceId ?: "--"}",
                "设备类型：${ProtocolDisplayText.productType(this, viewModel.productType)}",
                "工作状态：--",
                "控制模式：--",
                "电量：--",
                "线速度：--",
                "角速度：--",
                "设备状态：--",
                "运动状态：--",
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
                "工作状态：${ProtocolDisplayText.workStatus(this, status.workStatus)}",
                "控制模式：${ProtocolDisplayText.controlMode(this, status.controlMode)}",
                "电量：${status.batteryPercent?.let { "${it.toInt().coerceIn(0, 100)}%" } ?: "--"}",
                "线速度：${status.linearSpeedCms?.let { String.format(Locale.getDefault(), "%.1f cm/s", it) } ?: "--"}",
                "角速度：${status.angularSpeedRadps?.let { String.format(Locale.getDefault(), "%.2f rad/s", it) } ?: "--"}",
                "设备状态：${ProtocolDisplayText.deviceStatus(this, status.deviceStatus)}",
                "运动状态：${ProtocolDisplayText.movementStatus(this, status.movementStatus)}",
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
        findViewById<TextView?>(com.robot.solar.R.id.tvHomeStatusSummary)?.text =
            details.lineSequence().take(6).joinToString("\n")
    }

    private fun bindMap(mapState: MapUiState) {
        currentMapState = mapState
        val stateText = when (mapState.status) {
            MapLoadStatus.NO_MAP -> "暂无地图"
            MapLoadStatus.DOWNLOADING -> "正在加载"
            MapLoadStatus.READY -> if (mapState.isLocalDemo) "本地测试地图" else "地图已加载"
            MapLoadStatus.FAILED -> "地图加载失败"
        }
        binding.tvMapState.text = stateText
        binding.tvMapPageState.text = stateText
        val map = mapState.map
        val meta = if (map == null) {
            "--"
        } else {
            val source = if (mapState.isLocalDemo) "【测试数据】" else ""
            "$source 地图：${map.mapName ?: "--"}  编号：${map.mapId ?: "--"}  版本：${map.mapVersion ?: "--"}"
        }
        binding.tvMapMeta.text = meta
        binding.tvMapPageMeta.text = buildMapJsonSummary(mapState, map)
        val readyMap = mapState.pvMap.takeIf { mapState.status == MapLoadStatus.READY }
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

    private fun buildMapJsonSummary(mapState: MapUiState, notice: MapMessage?): String {
        val pvMap = mapState.pvMap
        return buildString {
            if (mapState.isLocalDemo) append("数据来源：本地测试 JSON\n")
            append("通知名称：${notice?.mapName ?: "--"}\n")
            append("通知编号：${notice?.mapId ?: "--"}\n")
            append("通知版本：${notice?.mapVersion ?: "--"}")
            if (pvMap == null) return@buildString
            append("\nJSON map_id：${pvMap.mapId}")
            append("\nJSON version：${pvMap.version}")
            currentPose?.let { pose ->
                append("\n机器人位置：B${pose.blockId ?: "--"} / C${pose.cellId ?: "--"} / ${ProtocolDisplayText.mapHeading(pose.headingCode, pose.heading)}")
            }
            append("\nsource：${pvMap.source?.type ?: "--"} / ${pvMap.source?.fileName ?: "--"}")
            pvMap.source?.generatedAt?.let { append(" / $it") }
            append("\nframe：${pvMap.frame.unit}")
            pvMap.frame.origin?.let { origin ->
                append(
                    String.format(
                        Locale.getDefault(),
                        "  lat=%.7f lon=%.7f yaw=%.1f°",
                        origin.latitudeDeg ?: 0.0,
                        origin.longitudeDeg ?: 0.0,
                        origin.yawDeg ?: 0.0
                    )
                )
            }
            append("\ncell_model：${pvMap.cellModel.innerRows} x ${pvMap.cellModel.innerCols}")
            append("\nblocks：${pvMap.blocks.size} 个，cells：${pvMap.cells.size} 个，bridges：${pvMap.bridges.size} 个")
            append("\nblock 明细：")
            append(
                pvMap.blocks.joinToString("；") { block ->
                    "B${block.blockId} ${block.rows}x${block.cols} cell=${block.cellIds.size} cleanable=${block.cleanable}"
                }
            )
            append("\ncell 明细：")
            append(
                pvMap.cells.joinToString("；") { cell ->
                    "C${cell.cellId}=B${cell.blockId}[${cell.row},${cell.col}]"
                }
            )
            append("\nbridge 明细：")
            append(
                if (pvMap.bridges.isEmpty()) {
                    "--"
                } else {
                    pvMap.bridges.joinToString("；") { bridge ->
                        val endpoints = bridge.endpoints.joinToString("<->") { endpoint ->
                            "B${endpoint.blockId}[${endpoint.cellRow},${endpoint.cellCol}] ${endpoint.edge} (${endpoint.innerRow},${endpoint.innerCol})"
                        }
                        "BR${bridge.bridgeId} ${bridge.source ?: "--"} $endpoints"
                    }
                }
            )
        }
    }

    private fun bindCommandState(state: CommandUiState) {
        val commandName = ProtocolDisplayText.commandName(this, state.cmd)
        val statusText = ProtocolDisplayText.commandStatus(this, state.status)
        binding.tvCommandState.text = "最近操作：$commandName · $statusText"
        if (state.status != CommandStatus.IDLE && state.status != CommandStatus.SENDING) {
            Toast.makeText(this, ProtocolDisplayText.commandFeedback(this, state.cmd, state.status), Toast.LENGTH_LONG).show()
        }
    }

    private fun bindAvailability(availability: ControlAvailability) {
        currentAvailability = availability
        binding.btnStart.isEnabled = availability.canStart
        binding.btnStopRun.isEnabled = availability.canStop
        binding.btnEmergency.isEnabled = availability.canEstop
        binding.btnRemoteEmergency.isEnabled = availability.canEstop
        binding.btnClearEstop.isEnabled = availability.canClearEstop
        binding.directionPad.controlsEnabled = availability.canRemote
        binding.btnRemoteStop.isEnabled = viewModel.mqttConnected.value == true
        binding.tvRemoteHint.text = if (availability.canRemote) {
            "长按方向按钮 0.5 秒后开始，松开立即停止"
        } else {
            remoteUnavailableReason()
        }
    }

    private fun showPage(page: Page) {
        if (currentPage == Page.REMOTE && page != Page.REMOTE) {
            binding.directionPad.cancelInput()
            viewModel.stopRemote(sendZero = true)
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
        val status = viewModel.status.value
        return when {
            viewModel.mqttConnected.value != true -> "MQTT 未连接，手动控制不可用"
            viewModel.deviceOnline.value != true -> "设备离线，手动控制不可用"
            status == null -> "等待设备状态"
            status.workStatus == "estopped" -> "设备处于急停状态"
            status.workStatus != "stopped" -> "请先停止自动运行"
            status.deviceStatus != "normal" -> "设备状态异常，手动控制不可用"
            status.controlMode != "manual" -> "设备尚未进入手动模式"
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
