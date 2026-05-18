package com.robot.solar.demo.repository

import android.content.Context
import com.robot.solar.demo.database.AppDatabase
import com.robot.solar.demo.entity.LogType
import com.robot.solar.demo.entity.SolarLogEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * 日志数据仓库：封装 Room 访问，供 ViewModel 与 LogUtils 复用
 */
class LogRepository private constructor(
    private val database: AppDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    fun observeLogsDesc(): Flow<List<SolarLogEntity>> = database.logDao().observeAllDesc()

    suspend fun insert(type: LogType, content: String) = withContext(ioDispatcher) {
        database.logDao().insert(
            SolarLogEntity(
                timestampMillis = System.currentTimeMillis(),
                type = type,
                content = content
            )
        )
    }

    suspend fun refreshAll(): List<SolarLogEntity> = withContext(ioDispatcher) {
        database.logDao().getAllDesc()
    }

    companion object {
        @Volatile
        private var INSTANCE: LogRepository? = null

        fun getInstance(context: Context): LogRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LogRepository(
                    database = AppDatabase.getInstance(context)
                ).also { INSTANCE = it }
            }
        }
    }
}
