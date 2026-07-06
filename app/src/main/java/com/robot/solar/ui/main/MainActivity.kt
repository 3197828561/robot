package com.robot.solar.ui.main

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.robot.solar.databinding.ActivityMainBinding
import com.robot.solar.network.mqtt.TelemetryMessage
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
        viewModel.telemetry.observe(this) { bindTelemetry(it) }
        viewModel.lastCmdFeedback.observe(this) { msg ->
            if (!msg.isNullOrBlank()) {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun bindTelemetry(msg: TelemetryMessage?) {
        if (msg == null) return
        val status = msg.status ?: return
        val locale = Locale.CHINA

        msg.timestampMs?.let { ts ->
            binding.tvInfoTimestamp.text = "时间戳：$ts"
        }
        status.yawDeg?.let {
            binding.tvInfoArrayDir.text = "阵列方向：航向 ${String.format(locale, "%.1f", it)}°"
        }
        status.pitchDeg?.let {
            binding.tvInfoSlope.text = "坡度：${String.format(locale, "%.1f", it)}°"
        }
        binding.tvInfoMotion.text = formatMotionState(status)
        binding.tvInfoDeviceState.text = when (status.faultCode) {
            0, null -> "设备状态：正常"
            else -> "设备状态：异常 (${status.faultCode})"
        }
        msg.navStatus?.navState?.let {
            binding.tvInfoJobState.text = formatJobState(it)
        }
        status.totalMileageM?.let {
            binding.tvTotalMileage.text = String.format(locale, "%,.0f m", it)
        }
        status.temperatureC?.let {
            binding.tvTemperature.text = String.format(locale, "%.1f °C", it)
        }
        status.pressureKpa?.let {
            binding.tvPressure.text = String.format(locale, "%.1f kPa", it)
        }
        status.cleanedRows?.let {
            binding.tvCleanedRows.text = "$it 行"
        }
        val left = status.antiFallLeftM
        val right = status.antiFallRightM
        if (left != null || right != null) {
            binding.tvAntiFallDistance.text = String.format(
                locale,
                "%.2f / %.2f m",
                left ?: 0.0,
                right ?: 0.0
            )
        }
    }

    private fun formatMotionState(status: com.robot.solar.network.mqtt.RoverStatus): String {
        status.motionState?.let { state ->
            return when (state) {
                0 -> "运动状态：静止"
                1 -> "运动状态：移动中"
                else -> "运动状态：状态码 $state"
            }
        }
        val v = status.linearVelocityMps
        return if (v != null && kotlin.math.abs(v) > 0.01) {
            "运动状态：移动中"
        } else {
            "运动状态：静止"
        }
    }

    private fun formatJobState(navState: Int): String = when (navState) {
        0 -> "作业状态：待机"
        1 -> "作业状态：作业中"
        else -> "作业状态：导航状态 $navState"
    }

    private fun updateBatteryUi(real: Int?) {
        if (real != null) {
            binding.progressBattery.progress = real
            binding.tvBatteryPercent.text = "$real%"
        }
    }

    private fun bindControls() {
        val linear = 0.15
        val angular = 0.4
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
