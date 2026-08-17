package com.robot.solar

import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.robot.solar.network.http.ApiClient
import com.robot.solar.network.mqtt.CloudCommMqttManager
import com.robot.solar.repository.LogRepository
import com.robot.solar.ui.login.LoginActivity
import com.robot.solar.utils.LogUtils

class SolarRobotApplication : Application() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()

        LogUtils.init(LogRepository.getInstance(this))
        LogUtils.system("应用启动")

        ApiClient.setAuthExpiredHandler {
            CloudCommMqttManager.shutdownIfInitialized()

            mainHandler.post {
                val intent = Intent(this, LoginActivity::class.java).apply {
                    flags =
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
            }
        }
    }
}
