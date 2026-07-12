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
import com.robot.solar.network.mqtt.CommandStatus
import com.robot.solar.network.mqtt.CommandUiState
import com.robot.solar.network.mqtt.MapLoadStatus
import com.robot.solar.network.mqtt.MapUiState
import com.robot.solar.network.mqtt.StatusMessage
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
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
    private var currentAvailability = ControlAvailability()

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
        binding.tvProductType.text = "设备类型：${viewModel.productType ?: "--"}"
        bindObservers()
        bindControls()
        showPage(Page.HOME)
    }

    override fun onStart() {
        super.onStart()
        viewModel.onScreenReady()
        clockHandler.post(clockRunnable)
    }

    override fun onPause() {
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
        viewModel.mapState.observe(this) { bindMap(it) }
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
        binding.btnRemoteEmergency.setOnClickListener { viewModel.sendCmd("紧急停止", "estop") }
        binding.btnReloadMap.setOnClickListener {
            Toast.makeText(this, binding.tvMapState.text, Toast.LENGTH_SHORT).show()
        }
        binding.btnCenterRobot.setOnClickListener {
            Toast.makeText(this, "暂无可居中的机器人位置", Toast.LENGTH_SHORT).show()
        }
        binding.btnViewLogs.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }

        binding.joystick.listener = object : JoystickView.Listener {
            override fun onStart(x: Double, y: Double) {
                val speeds = toSpeeds(x, y)
                if (currentAvailability.canRemote) viewModel.startRemote(speeds.first, speeds.second)
            }

            override fun onMove(x: Double, y: Double) {
                val speeds = toSpeeds(x, y)
                if (currentAvailability.canRemote) viewModel.updateRemote(speeds.first, speeds.second)
            }

            override fun onStop() {
                viewModel.stopRemote(sendZero = true)
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

    private fun toSpeeds(x: Double, y: Double): Pair<Double, Double> {
        val linear = (y * 20.0).coerceIn(-20.0, 20.0)
        val angular = (x * 0.5).coerceIn(-0.5, 0.5)
        return linear to angular
    }

    private fun bindStatus(status: StatusMessage?) {
        val details = if (status == null) {
            listOf(
                "设备名称：${viewModel.deviceDisplayName ?: "--"}",
                "设备编号：${viewModel.deviceId ?: "--"}",
                "设备类型：${viewModel.productType ?: "--"}",
                "工作状态：--",
                "控制模式：--",
                "电量：--",
                "线速度：--",
                "角速度：--",
                "设备状态：--",
                "运动状态：--",
                "当前区域：--",
                "当前单元：--",
                "最后在线时间：${binding.tvLastHeartbeat.text.removePrefix("最后在线时间：")}"
            ).joinToString("\n")
        } else {
            listOf(
                "设备名称：${viewModel.deviceDisplayName ?: "--"}",
                "设备编号：${viewModel.deviceId ?: "--"}",
                "设备类型：${viewModel.productType ?: "--"}",
                "工作状态：${formatWorkStatus(status.workStatus)}",
                "控制模式：${status.controlMode ?: "--"}",
                "电量：${status.batteryPercent?.let { "${it.toInt().coerceIn(0, 100)}%" } ?: "--"}",
                "线速度：${status.linearSpeedCms?.let { String.format(Locale.CHINA, "%.1f cm/s", it) } ?: "--"}",
                "角速度：${status.angularSpeedRadps?.let { String.format(Locale.CHINA, "%.2f rad/s", it) } ?: "--"}",
                "设备状态：${formatDeviceStatus(status.deviceStatus)}",
                "运动状态：${formatMovementStatus(status.movementStatus)}",
                "当前区域：${status.currentBlockId ?: "--"}",
                "当前单元：${status.currentCellId ?: "--"}",
                "最后在线时间：${binding.tvLastHeartbeat.text.removePrefix("最后在线时间：")}"
            ).joinToString("\n")
        }
        binding.tvStatusDetails.text = details
        binding.tvRemoteStatus.text = details
        findViewById<TextView?>(com.robot.solar.R.id.tvHomeStatusSummary)?.text =
            details.lineSequence().take(6).joinToString("\n")
    }

    private fun bindMap(mapState: MapUiState) {
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
            "地图编号：${map.mapId ?: "--"}  版本：${map.mapVersion ?: "--"}"
        }
        binding.tvMapMeta.text = meta
        binding.tvMapPageMeta.text = "地图版本：${map?.mapVersion ?: "--"}\n地图编号：${map?.mapId ?: "--"}"
    }

    private fun bindCommandState(state: CommandUiState) {
        val label = when (state.status) {
            CommandStatus.IDLE -> "--"
            CommandStatus.SENDING -> "发送中"
            CommandStatus.SUCCESS -> "成功"
            CommandStatus.FAILED -> "失败"
            CommandStatus.TIMEOUT -> "超时"
            CommandStatus.CONNECTION_LOST -> "连接异常"
        }
        binding.tvCommandState.text = "最近命令：${state.cmd ?: "--"} / $label / ${state.cmdId ?: "--"}"
        if (state.status != CommandStatus.IDLE && state.status != CommandStatus.SENDING) {
            Toast.makeText(this, state.message ?: label, Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindAvailability(availability: ControlAvailability) {
        currentAvailability = availability
        binding.btnStart.isEnabled = availability.canStart
        binding.btnStopRun.isEnabled = availability.canStop
        binding.btnEmergency.isEnabled = availability.canEstop
        binding.btnRemoteEmergency.isEnabled = availability.canEstop
        binding.btnClearEstop.isEnabled = availability.canClearEstop
        binding.joystick.alpha = if (availability.canRemote) 1.0f else 0.72f
        binding.tvRemoteHint.text = if (availability.canRemote) {
            "松开摇杆自动停止，支持 360° 全向控制"
        } else {
            "当前条件未满足：摇杆可预览拖动，但不会发送遥控"
        }
    }

    private fun showPage(page: Page) {
        binding.sectionHome.visibility = if (page == Page.HOME) View.VISIBLE else View.GONE
        binding.sectionMap.visibility = if (page == Page.MAP) View.VISIBLE else View.GONE
        binding.sectionRemote.visibility = if (page == Page.REMOTE) View.VISIBLE else View.GONE
        binding.sectionStatus.visibility = if (page == Page.STATUS) View.VISIBLE else View.GONE
        selectNav(binding.navHome, page == Page.HOME)
        selectNav(binding.navMap, page == Page.MAP)
        selectNav(binding.navRemote, page == Page.REMOTE)
        selectNav(binding.navStatus, page == Page.STATUS)
    }

    private fun selectNav(view: TextView, selected: Boolean) {
        view.setTextColor(getColor(if (selected) com.robot.solar.R.color.control_primary else com.robot.solar.R.color.control_nav_inactive))
        view.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    private fun formatWorkStatus(status: String?): String = when (status) {
        null -> "--"
        "idle" -> "待机"
        "running" -> "运行中"
        "stopped" -> "已停止"
        "estopped" -> "急停锁存"
        "fault" -> "故障"
        else -> status
    }

    private fun formatDeviceStatus(status: String?): String = when (status) {
        null -> "--"
        "normal" -> "正常"
        "warning" -> "告警"
        "fault" -> "故障"
        else -> status
    }

    private fun formatMovementStatus(status: String?): String = when (status) {
        null -> "--"
        "moving" -> "移动中"
        "stopped" -> "静止"
        "turning" -> "转向中"
        "blocked" -> "受阻"
        else -> status
    }
}

private enum class Page {
    HOME,
    MAP,
    REMOTE,
    STATUS
}
