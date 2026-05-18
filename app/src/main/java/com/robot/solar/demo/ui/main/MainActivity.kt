package com.robot.solar.demo.ui.main

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.robot.solar.demo.databinding.ActivityMainBinding
import com.robot.solar.demo.ui.log.LogActivity
import com.robot.solar.demo.viewmodel.MainViewModel

/**
 * 光伏机器人遥控主页：展示 MQTT 与设备状态、发送 JSON 指令、跳转日志页
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bindObservers()
        bindControls()
        updateBatteryUi(viewModel.batteryPercent.value)
    }

    override fun onStart() {
        super.onStart()
        viewModel.onScreenReady()
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
        viewModel.simulatedBattery.observe(this) { updateBatteryUi(viewModel.batteryPercent.value) }
    }

    /** 有远端上报电量时优先展示，否则使用本地估算电量 */
    private fun updateBatteryUi(real: Int?) {
        val sim = viewModel.simulatedBattery.value ?: 78
        if (real != null) {
            binding.progressBattery.progress = real
            binding.tvBatteryPercent.text = "$real%"
        } else {
            binding.progressBattery.progress = sim
            binding.tvBatteryPercent.text = "$sim%"
        }
    }

    private fun bindControls() {
        binding.btnForward.setOnClickListener {
            viewModel.sendRobotCommand(label = "前进", cmd = "forward")
        }
        binding.btnBackward.setOnClickListener {
            viewModel.sendRobotCommand(label = "后退", cmd = "backward")
        }
        binding.btnLeft.setOnClickListener {
            viewModel.sendRobotCommand(label = "左转", cmd = "turn_left")
        }
        binding.btnRight.setOnClickListener {
            viewModel.sendRobotCommand(label = "右转", cmd = "turn_right")
        }
        binding.btnStop.setOnClickListener {
            viewModel.sendRobotCommand(label = "停止", cmd = "stop")
        }
        binding.btnEmergency.setOnClickListener {
            viewModel.sendRobotCommand(label = "急停", cmd = "emergency_stop")
        }
        binding.btnViewLogs.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
    }
}
