package com.robot.solar.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.robot.solar.entity.SolarLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SolarLogEntity): Long

    @Query("SELECT * FROM solar_logs ORDER BY timestamp DESC")
    fun observeAllDesc(): Flow<List<SolarLogEntity>>
}
