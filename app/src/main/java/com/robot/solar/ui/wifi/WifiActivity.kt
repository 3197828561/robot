package com.robot.solar.ui.wifi

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.robot.solar.databinding.ActivityWifiBinding
import com.robot.solar.repository.DeviceRepository
import com.robot.solar.repository.WifiRepository
import com.robot.solar.ui.common.applySystemBarPadding
import kotlinx.coroutines.launch

class WifiActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWifiBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWifiBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarPadding()

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
            MaterialAlertDialogBuilder(this)
                .setTitle("确认修改Wi-Fi")
                .setMessage("修改后机器人可能暂时离线。请确认SSID和密码正确，并确保现场可以恢复网络。")
                .setNegativeButton("取消", null)
                .setPositiveButton("提交配置") { _, _ ->
                    binding.btnSave.isEnabled = false
                    lifecycleScope.launch {
                        try {
                            repo.update(deviceId, ssid, pwd)
                            Toast.makeText(
                                this@WifiActivity,
                                "配置请求已提交，请确认机器人重新上线",
                                Toast.LENGTH_LONG
                            ).show()
                            finish()
                        } catch (e: Exception) {
                            binding.btnSave.isEnabled = true
                            Toast.makeText(this@WifiActivity, "配置请求失败，请检查网络和权限", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .show()
        }
    }
}
