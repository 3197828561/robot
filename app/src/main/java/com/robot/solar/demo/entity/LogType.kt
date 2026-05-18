package com.robot.solar.demo.entity

/**
 * 本地日志类型枚举（与业务模块对应，便于筛选与展示）
 */
enum class LogType(val code: Int, val displayName: String) {
    /** 用户登录、登出等认证相关 */
    LOGIN(0, "登录日志"),

    /** 机器人遥控指令、急停等设备侧操作 */
    DEVICE(1, "设备操作日志"),

    /** 应用生命周期、MQTT 连接异常等系统级事件 */
    SYSTEM(2, "系统日志");

    companion object {
        fun fromCode(code: Int): LogType = entries.find { it.code == code } ?: SYSTEM
    }
}
