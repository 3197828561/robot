package com.robot.solar.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.robot.solar.entity.StructuredLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: StructuredLogEntity): Long

    @Query("SELECT * FROM app_logs ORDER BY timestampMillis DESC LIMIT 2000")
    fun observeAllDesc(): Flow<List<StructuredLogEntity>>

    @Query(
        """
        SELECT * FROM app_logs
        WHERE category = 'COMMAND' AND eventType = 'command'
        ORDER BY timestampMillis DESC
        LIMIT :limit
        """
    )
    fun observeRecentCommands(limit: Int): Flow<List<StructuredLogEntity>>

    @Query("DELETE FROM app_logs WHERE timestampMillis < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long)

    @Query(
        """
        DELETE FROM app_logs
        WHERE id NOT IN (
            SELECT id FROM app_logs ORDER BY timestampMillis DESC LIMIT :maxRows
        )
        """
    )
    suspend fun trimToNewest(maxRows: Int)

    @Query("DELETE FROM app_logs")
    suspend fun deleteAll()
}
