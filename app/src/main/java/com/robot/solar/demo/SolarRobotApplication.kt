package com.robot.solar.demo

import android.app.Application
import com.robot.solar.demo.repository.LogRepository
import com.robot.solar.demo.utils.LogUtils

/**
 * 自定义 Application：初始化全局日志工具与首条系统日志
 */
class SolarRobotApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val logRepository = LogRepository.getInstance(this)
        LogUtils.init(logRepository)
        LogUtils.system("应用程序进程启动，日志子系统已就绪")
    }
}
