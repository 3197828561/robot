package com.robot.solar.demo.ui.splash

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.robot.solar.demo.databinding.ActivitySplashBinding
import com.robot.solar.demo.repository.LoginRepository
import com.robot.solar.demo.ui.login.LoginActivity
import com.robot.solar.demo.ui.main.MainActivity
import com.robot.solar.demo.utils.LogUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 启动页：短暂展示品牌后，根据「自动登录」配置跳转登录或主页
 */
@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch {
            delay(700)
            val loginRepository = LoginRepository.getInstance(applicationContext)
            val autoOk = loginRepository.tryAutoLogin()
            if (autoOk) {
                LogUtils.login("自动登录成功")
                startActivity(Intent(this@SplashActivity, MainActivity::class.java))
            } else {
                startActivity(Intent(this@SplashActivity, LoginActivity::class.java))
            }
            finish()
        }
    }
}
