package com.robot.solar.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "solar_logs")
data class SolarLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "timestamp")
    val timestampMillis: Long,
    @ColumnInfo(name = "type")
    val type: LogType,
    @ColumnInfo(name = "content")
    val content: String
)
