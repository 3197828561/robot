package com.robot.solar.ui.firmware

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.robot.solar.databinding.ActivityFirmwareBinding
import com.robot.solar.repository.DeviceRepository
import com.robot.solar.repository.FirmwareRepository
import kotlinx.coroutines.launch

class FirmwareActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFirmwareBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFirmwareBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        val deviceId = DeviceRepository.getInstance(this).currentDeviceId()
        if (deviceId.isNullOrBlank()) {
            Toast.makeText(this, "未选择设备", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val repo = FirmwareRepository.getInstance(this)

        lifecycleScope.launch {
            try {
                val meta = repo.latest(deviceId)
                binding.tvVersion.text = "版本：${meta.version}"
                binding.tvNotes.text = meta.releaseNotes ?: "暂无说明"
            } catch (e: Exception) {
                binding.tvVersion.text = "版本：加载失败"
                Toast.makeText(this@FirmwareActivity, e.message, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnUpgrade.setOnClickListener {
            lifecycleScope.launch {
                try {
                    val resp = repo.upgrade(deviceId, null)
                    Toast.makeText(this@FirmwareActivity, resp.message, Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this@FirmwareActivity, "升级请求失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
