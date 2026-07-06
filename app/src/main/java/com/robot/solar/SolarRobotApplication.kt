package com.robot.solar

import android.app.Application
import com.robot.solar.repository.LogRepository
import com.robot.solar.utils.LogUtils

class SolarRobotApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LogUtils.init(LogRepository.getInstance(this))
        LogUtils.system("应用启动")
    }
}
