package com.robot.solar.database

import androidx.room.TypeConverter
import com.robot.solar.entity.LogCategory
import com.robot.solar.entity.LogDirection
import com.robot.solar.entity.LogSeverity
import com.robot.solar.entity.LogSource

class Converters {
    @TypeConverter
    fun fromLogSource(value: LogSource): String = value.name

    @TypeConverter
    fun toLogSource(value: String): LogSource =
        runCatching { LogSource.valueOf(value) }.getOrDefault(LogSource.LEGACY)

    @TypeConverter
    fun fromLogCategory(value: LogCategory): String = value.name

    @TypeConverter
    fun toLogCategory(value: String): LogCategory =
        runCatching { LogCategory.valueOf(value) }.getOrDefault(LogCategory.SYSTEM)

    @TypeConverter
    fun fromLogSeverity(value: LogSeverity): String = value.name

    @TypeConverter
    fun toLogSeverity(value: String): LogSeverity =
        runCatching { LogSeverity.valueOf(value) }.getOrDefault(LogSeverity.INFO)

    @TypeConverter
    fun fromLogDirection(value: LogDirection): String = value.name

    @TypeConverter
    fun toLogDirection(value: String): LogDirection =
        runCatching { LogDirection.valueOf(value) }.getOrDefault(LogDirection.LOCAL)
}
