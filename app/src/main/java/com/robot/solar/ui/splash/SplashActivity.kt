package com.robot.solar.ui.splash

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.robot.solar.databinding.ActivitySplashBinding
import com.robot.solar.repository.AuthRepository
import com.robot.solar.ui.device.DeviceListActivity
import com.robot.solar.ui.login.LoginActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch {
            delay(600)
            val auth = AuthRepository.getInstance(applicationContext)
            val nextActivity = if (auth.isLoggedIn()) {
                DeviceListActivity::class.java
            } else {
                LoginActivity::class.java
            }
            startActivity(Intent(this@SplashActivity, nextActivity))
            finish()
        }
    }
}
