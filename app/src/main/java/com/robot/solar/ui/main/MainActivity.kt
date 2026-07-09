package com.robot.solar.ui.main

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.robot.solar.databinding.ActivityMainBinding
import com.robot.solar.network.mqtt.StatusMessage
import com.robot.solar.ui.firmware.FirmwareActivity
import com.robot.solar.ui.job.JobListActivity
import com.robot.solar.ui.log.LogActivity
import com.robot.solar.ui.wifi.WifiActivity
import com.robot.solar.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private val clockHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.CHINA)

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

        binding.tvDeviceName.text = viewModel.deviceDisplayName ?: "--"
        bindObservers()
        bindControls()
        updateBatteryUi(viewModel.batteryPercent.value)
    }

    override fun onStart() {
        super.onStart()
        viewModel.onScreenReady()
        clockHandler.post(clockRunnable)
    }

    override fun onStop() {
        clockHandler.removeCallbacks(clockRunnable)
        super.onStop()
    }

    private fun bindObservers() {
        viewModel.mqttConnected.observe(this) { connected ->
            binding.tvMqttStatus.text = if (connected) "已连接" else "未连接 / 重连中"
        }
        viewModel.deviceOnline.observe(this) { online ->
            binding.tvDeviceOnline.text = when (online) {
                true -> "在线"
                false -> "离线"
                null -> "等待状态上报"
            }
        }
        viewModel.batteryPercent.observe(this) { updateBatteryUi(it) }
        viewModel.status.observe(this) { bindStatus(it) }
        viewModel.lastCmdFeedback.observe(this) { msg ->
            if (!msg.isNullOrBlank()) {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun bindStatus(msg: StatusMessage?) {
        if (msg == null) return
        val locale = Locale.CHINA

        msg.timestamp?.let { ts ->
            binding.tvInfoTimestamp.text = "时间戳：$ts"
        }
        msg.yawDeg?.let {
            binding.tvInfoArrayDir.text = "阵列方向：航向 ${String.format(locale, "%.1f", it)}°"
        }
        msg.pitchDeg?.let {
            binding.tvInfoSlope.text = "坡度：${String.format(locale, "%.1f", it)}°"
        }
        binding.tvInfoMotion.text = formatMotionState(msg)
        binding.tvInfoDeviceState.text = "设备状态：${formatDeviceStatus(msg.deviceStatus)}"
        msg.workStatus?.let { binding.tvInfoJobState.text = "作业状态：${formatWorkStatus(it)}" }
        msg.totalMileageM?.let {
            binding.tvTotalMileage.text = String.format(locale, "%,.0f m", it)
        }
        msg.temperatureC?.let {
            binding.tvTemperature.text = String.format(locale, "%.1f °C", it)
        }
        msg.pressureKpa?.let {
            binding.tvPressure.text = String.format(locale, "%.1f kPa", it)
        }
        msg.cleanedRows?.let {
            binding.tvCleanedRows.text = "$it 行"
        }
        val left = msg.antiFallLeftM
        val right = msg.antiFallRightM
        if (left != null || right != null) {
            binding.tvAntiFallDistance.text = String.format(
                locale,
                "%.2f / %.2f m",
                left ?: 0.0,
                right ?: 0.0
            )
        }
    }

    private fun formatMotionState(status: StatusMessage): String = when (status.movementStatus) {
        "moving" -> "运动状态：移动中"
        "stopped" -> "运动状态：静止"
        "turning" -> "运动状态：转向中"
        "blocked" -> "运动状态：受阻"
        null -> if (kotlin.math.abs(status.linearSpeedCms ?: 0.0) > 0.1) "运动状态：移动中" else "运动状态：静止"
        else -> "运动状态：${status.movementStatus}"
    }

    private fun formatDeviceStatus(status: String?): String = when (status) {
        "normal", null -> "正常"
        "warning" -> "告警"
        "fault" -> "故障"
        else -> status
    }

    private fun formatWorkStatus(status: String): String = when (status) {
        "idle" -> "待机"
        "running" -> "运行中"
        "stopped" -> "已停止"
        "estopped" -> "急停锁存"
        "fault" -> "故障"
        else -> status
    }

    private fun updateBatteryUi(real: Int?) {
        if (real != null) {
            binding.progressBattery.progress = real
            binding.tvBatteryPercent.text = "$real%"
        }
    }

    private fun bindControls() {
        val linear = 20.0
        val angular = 0.3
        binding.btnForward.setOnClickListener {
            viewModel.sendRemote("前进", linear, 0.0)
        }
        binding.btnBackward.setOnClickListener {
            viewModel.sendRemote("后退", -linear, 0.0)
        }
        binding.btnLeft.setOnClickListener {
            viewModel.sendRemote("左转", 0.0, angular)
        }
        binding.btnRight.setOnClickListener {
            viewModel.sendRemote("右转", 0.0, -angular)
        }
        binding.btnStop.setOnClickListener {
            viewModel.sendRemote("停止", 0.0, 0.0)
        }
        binding.btnEmergency.setOnClickListener {
            viewModel.sendCmd("急停", "estop")
        }
        binding.btnRemoteStart.setOnClickListener {
            viewModel.sendCmd("远程启动", "start")
        }
        binding.btnViewLogs.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        binding.btnJobRecord.setOnClickListener {
            startActivity(Intent(this, JobListActivity::class.java))
        }
        binding.btnFirmware.setOnClickListener {
            startActivity(Intent(this, FirmwareActivity::class.java))
        }
        binding.btnWifi.setOnClickListener {
            startActivity(Intent(this, WifiActivity::class.java))
        }
        binding.btnRemoteMode.setOnClickListener {
            Toast.makeText(this, "当前页即为遥控模式", Toast.LENGTH_SHORT).show()
        }
        binding.btnManual.setOnClickListener {
            Toast.makeText(this, "说明书请参考产品文档", Toast.LENGTH_SHORT).show()
        }
    }
}
