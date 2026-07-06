package com.robot.solar.ui.splash

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.robot.solar.databinding.ActivitySplashBinding
import com.robot.solar.repository.AuthRepository
import com.robot.solar.repository.DeviceRepository
import com.robot.solar.ui.device.DeviceListActivity
import com.robot.solar.ui.login.LoginActivity
import com.robot.solar.ui.main.MainActivity
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
            val device = DeviceRepository.getInstance(applicationContext)
            when {
                !auth.isLoggedIn() ->
                    startActivity(Intent(this@SplashActivity, LoginActivity::class.java))
                !device.hasDevice() ->
                    startActivity(Intent(this@SplashActivity, DeviceListActivity::class.java))
                else ->
                    startActivity(Intent(this@SplashActivity, MainActivity::class.java))
            }
            finish()
        }
    }
}
