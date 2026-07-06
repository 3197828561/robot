package com.robot.solar.database

import androidx.room.TypeConverter
import com.robot.solar.entity.LogType

class Converters {
    @TypeConverter
    fun fromLogType(type: LogType): Int = type.code

    @TypeConverter
    fun toLogType(code: Int): LogType = LogType.fromCode(code)
}
