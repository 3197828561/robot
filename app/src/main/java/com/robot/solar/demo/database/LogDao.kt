package com.robot.solar.demo.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.robot.solar.demo.entity.SolarLogEntity
import kotlinx.coroutines.flow.Flow

/**
 * 日志表访问对象：提供插入与按时间倒序查询
 */
@Dao
interface LogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SolarLogEntity): Long

    @Query("SELECT * FROM solar_logs ORDER BY timestamp DESC")
    fun observeAllDesc(): Flow<List<SolarLogEntity>>

    @Query("SELECT * FROM solar_logs ORDER BY timestamp DESC")
    suspend fun getAllDesc(): List<SolarLogEntity>
}
