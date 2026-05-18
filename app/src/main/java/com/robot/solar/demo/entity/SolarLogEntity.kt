package com.robot.solar.demo.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room 持久化的单条日志实体
 *
 * @param id 自增主键
 * @param timestampMillis 日志产生时间（毫秒时间戳，列表按此倒序）
 * @param type 日志类型（登录 / 设备 / 系统）
 * @param content 详细描述内容
 */
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
