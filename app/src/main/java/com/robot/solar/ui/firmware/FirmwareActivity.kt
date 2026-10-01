package com.robot.solar.ui.firmware

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.robot.solar.databinding.ActivityFirmwareBinding
import com.robot.solar.repository.DeviceRepository
import com.robot.solar.repository.FirmwareRepository
import com.robot.solar.ui.common.applySystemBarPadding
import kotlinx.coroutines.launch

class FirmwareActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFirmwareBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFirmwareBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarPadding()

        binding.toolbar.setNavigationOnClickListener { finish() }

        val deviceId = DeviceRepository.getInstance(this).currentDeviceId()
        if (deviceId.isNullOrBlank()) {
            Toast.makeText(this, "未选择设备", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val repo = FirmwareRepository.getInstance(this)
        var targetVersion: String? = null
        binding.btnUpgrade.isEnabled = false

        lifecycleScope.launch {
            try {
                val meta = repo.latest(deviceId)
                targetVersion = meta.version
                binding.tvVersion.text = "版本：${meta.version}"
                binding.tvNotes.text = meta.releaseNotes ?: "暂无说明"
                binding.btnUpgrade.isEnabled = true
            } catch (e: Exception) {
                binding.tvVersion.text = "版本：加载失败"
                Toast.makeText(this@FirmwareActivity, getString(com.robot.solar.R.string.error_load_failed), Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnUpgrade.setOnClickListener {
            val version = targetVersion ?: return@setOnClickListener
            MaterialAlertDialogBuilder(this)
                .setTitle("确认升级Robot固件")
                .setMessage("目标版本：$version\n升级期间不要断电或操作机器人。当前页面只提交升级请求，最终结果以Robot状态为准。")
                .setNegativeButton("取消", null)
                .setPositiveButton("提交升级") { _, _ ->
                    binding.btnUpgrade.isEnabled = false
                    lifecycleScope.launch {
                        try {
                            repo.upgrade(deviceId, version)
                            Toast.makeText(
                                this@FirmwareActivity,
                                getString(com.robot.solar.R.string.firmware_upgrade_submitted),
                                Toast.LENGTH_LONG
                            ).show()
                        } catch (e: Exception) {
                            binding.btnUpgrade.isEnabled = true
                            Toast.makeText(this@FirmwareActivity, "升级请求失败，请检查网络和权限", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .show()
        }
    }
}
