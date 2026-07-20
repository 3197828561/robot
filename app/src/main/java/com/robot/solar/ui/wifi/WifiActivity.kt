package com.robot.solar.ui.wifi

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.robot.solar.databinding.ActivityWifiBinding
import com.robot.solar.repository.DeviceRepository
import com.robot.solar.repository.WifiRepository
import kotlinx.coroutines.launch

class WifiActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWifiBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWifiBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        val deviceId = DeviceRepository.getInstance(this).currentDeviceId()
        if (deviceId.isNullOrBlank()) {
            Toast.makeText(this, "未选择设备", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val repo = WifiRepository.getInstance(this)

        lifecycleScope.launch {
            try {
                val cfg = repo.get(deviceId)
                binding.etSsid.setText(cfg.ssid.orEmpty())
            } catch (e: Exception) {
                Toast.makeText(this@WifiActivity, getString(com.robot.solar.R.string.error_read_failed), Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSave.setOnClickListener {
            val ssid = binding.etSsid.text?.toString().orEmpty()
            val pwd = binding.etPassword.text?.toString().orEmpty()
            if (ssid.isBlank() || pwd.isBlank()) {
                Toast.makeText(this, "请填写 SSID 和密码", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                try {
                    repo.update(deviceId, ssid, pwd)
                    Toast.makeText(this@WifiActivity, "WiFi 配置已保存", Toast.LENGTH_SHORT).show()
                    finish()
                } catch (e: Exception) {
                    Toast.makeText(this@WifiActivity, "保存失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
