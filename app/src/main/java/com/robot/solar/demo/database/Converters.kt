package com.robot.solar.demo.database

import androidx.room.TypeConverter
import com.robot.solar.demo.entity.LogType

/**
 * Room 类型转换器：枚举与整型存储互转
 */
class Converters {

    @TypeConverter
    fun fromLogType(type: LogType): Int = type.code

    @TypeConverter
    fun toLogType(code: Int): LogType = LogType.fromCode(code)
}
