package com.robot.solar.ui.about

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.robot.solar.BuildConfig
import com.robot.solar.databinding.ActivityAboutBinding
import com.robot.solar.ui.common.applySystemBarPadding
import com.robot.solar.update.AppUpdateManager

class AboutActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAboutBinding
    private val appUpdateManager by lazy { AppUpdateManager(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarPadding()

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.tvVersionName.text = "版本名称：${BuildConfig.VERSION_NAME}"
        binding.tvVersionCode.text = "版本编号：${BuildConfig.VERSION_CODE}"
        binding.tvChannel.text = "更新渠道：${BuildConfig.APP_UPDATE_CHANNEL}"
        binding.tvCommit.text = "构建提交：${BuildConfig.BUILD_GIT_COMMIT.take(12)}"
        binding.btnCheckUpdate.setOnClickListener { appUpdateManager.check() }
    }

    override fun onResume() {
        super.onResume()
        appUpdateManager.resumePendingInstall()
    }
}
