package com.robot.solar.utils

import com.robot.solar.entity.LogType
import com.robot.solar.repository.LogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object LogUtils {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var repository: LogRepository? = null

    fun init(repository: LogRepository) {
        this.repository = repository
    }

    private fun write(type: LogType, content: String) {
        val repo = repository ?: return
        appScope.launch {
            try {
                repo.insert(type, content)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun login(content: String) = write(LogType.LOGIN, content)
    fun device(content: String) = write(LogType.DEVICE, content)
    fun system(content: String) = write(LogType.SYSTEM, content)
}
