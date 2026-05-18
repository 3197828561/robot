package com.robot.solar.demo.utils

import com.robot.solar.demo.entity.LogType
import com.robot.solar.demo.repository.LogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 全局日志写入工具：任意位置调用即可异步写入 Room，不阻塞调用线程
 *
 * 使用前必须在 [SolarRobotApplication] 中调用 [init]。
 */
object LogUtils {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var repository: LogRepository? = null

    /**
     * 注入日志仓库（应用启动时调用一次即可）
     */
    fun init(repository: LogRepository) {
        this.repository = repository
    }

    private fun write(type: LogType, content: String) {
        val repo = repository ?: return
        appScope.launch {
            try {
                repo.insert(type, content)
            } catch (e: Exception) {
                // 日志写入失败不应影响主流程；避免递归调用自身
                e.printStackTrace()
            }
        }
    }

    /** 登录相关日志 */
    fun login(content: String) = write(LogType.LOGIN, content)

    /** 设备遥控相关日志 */
    fun device(content: String) = write(LogType.DEVICE, content)

    /** 系统事件日志 */
    fun system(content: String) = write(LogType.SYSTEM, content)
}
