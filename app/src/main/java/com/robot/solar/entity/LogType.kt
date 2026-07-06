package com.robot.solar.entity

enum class LogType(val code: Int, val displayName: String) {
    LOGIN(0, "登录日志"),
    DEVICE(1, "设备操作日志"),
    SYSTEM(2, "系统日志");

    companion object {
        fun fromCode(code: Int): LogType = entries.find { it.code == code } ?: SYSTEM
    }
}
