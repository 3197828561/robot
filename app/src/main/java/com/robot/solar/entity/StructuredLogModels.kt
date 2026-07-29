package com.robot.solar.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class LogSource(val displayName: String) {
    APP("APP"),
    ROBOT("机器人"),
    MQTT("MQTT"),
    HTTP("HTTP"),
    LEGACY("旧版")
}

enum class LogCategory(val displayName: String) {
    AUTH("账号"),
    CONNECTION("连接"),
    COMMAND("命令"),
    TASK("任务"),
    REMOTE("遥控"),
    MAP("地图"),
    SAFETY("安全"),
    DEVICE("设备"),
    SYSTEM("系统")
}

enum class LogSeverity(val displayName: String) {
    DEBUG("调试"),
    INFO("信息"),
    WARNING("警告"),
    ERROR("错误"),
    CRITICAL("严重")
}

enum class LogDirection {
    LOCAL,
    UPSTREAM,
    DOWNSTREAM
}

enum class LogFilter {
    ALL,
    OPERATIONS,
    DEVICE,
    CONNECTION,
    ERRORS
}

@Entity(
    tableName = "app_logs",
    indices = [
        Index(value = ["eventId"], unique = true),
        Index(value = ["timestampMillis"]),
        Index(value = ["deviceId", "timestampMillis"]),
        Index(value = ["category", "timestampMillis"]),
        Index(value = ["cmdId"]),
        Index(value = ["missionId"])
    ]
)
data class StructuredLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val eventId: String,
    val timestampMillis: Long,
    val deviceId: String? = null,
    val productType: String? = null,
    val source: LogSource,
    val category: LogCategory,
    val eventType: String,
    val severity: LogSeverity = LogSeverity.INFO,
    val direction: LogDirection = LogDirection.LOCAL,
    val topic: String? = null,
    val cmdId: String? = null,
    val missionId: String? = null,
    val action: String? = null,
    val result: String? = null,
    val summary: String,
    val detailJson: String? = null,
    val dedupeKey: String? = null,
    val repeatCount: Int = 1
)

data class StructuredLogDraft(
    val eventId: String? = null,
    val timestampMillis: Long = System.currentTimeMillis(),
    val deviceId: String? = null,
    val productType: String? = null,
    val source: LogSource,
    val category: LogCategory,
    val eventType: String,
    val severity: LogSeverity = LogSeverity.INFO,
    val direction: LogDirection = LogDirection.LOCAL,
    val topic: String? = null,
    val cmdId: String? = null,
    val missionId: String? = null,
    val action: String? = null,
    val result: String? = null,
    val summary: String,
    val detailJson: String? = null,
    val dedupeKey: String? = null
)
