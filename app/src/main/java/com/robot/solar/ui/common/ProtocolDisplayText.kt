package com.robot.solar.ui.common

import android.content.Context
import com.robot.solar.R
import com.robot.solar.network.mqtt.CommandStatus

object ProtocolDisplayText {
    fun workStatus(context: Context, value: String?): String = context.getString(
        when (value) {
            "idle" -> R.string.work_status_idle
            "running" -> R.string.work_status_running
            "stopped" -> R.string.work_status_stopped
            "estopped" -> R.string.work_status_estopped
            "fault" -> R.string.status_fault
            null -> R.string.value_unavailable
            else -> R.string.status_unknown
        }
    )

    fun controlMode(context: Context, value: String?): String = context.getString(
        when (value) {
            "auto" -> R.string.control_mode_auto
            "manual" -> R.string.control_mode_manual
            null -> R.string.value_unavailable
            else -> R.string.control_mode_unknown
        }
    )

    fun deviceStatus(context: Context, value: String?): String = context.getString(
        when (value) {
            "normal" -> R.string.status_normal
            "warning" -> R.string.status_warning
            "fault" -> R.string.status_fault
            null -> R.string.value_unavailable
            else -> R.string.status_unknown
        }
    )

    fun movementStatus(context: Context, value: String?): String = context.getString(
        when (value) {
            "moving" -> R.string.movement_moving
            "stopped" -> R.string.movement_stopped
            "turning" -> R.string.movement_turning
            "blocked" -> R.string.movement_blocked
            null -> R.string.value_unavailable
            else -> R.string.status_unknown
        }
    )

    fun commandName(context: Context, value: String?): String = context.getString(
        when (value) {
            "start" -> R.string.command_start
            "stop" -> R.string.command_stop
            "pause" -> R.string.command_pause
            "resume" -> R.string.command_resume
            "replan" -> R.string.command_replan
            "manual" -> R.string.command_manual
            "auto" -> R.string.command_auto
            "estop" -> R.string.command_estop
            "clear_estop" -> R.string.command_clear_estop
            null -> R.string.value_unavailable
            else -> R.string.command_unknown
        }
    )

    fun commandStatus(context: Context, value: CommandStatus): String = context.getString(
        when (value) {
            CommandStatus.IDLE -> R.string.value_unavailable
            CommandStatus.SENDING -> R.string.command_status_sending
            CommandStatus.SUCCESS -> R.string.command_status_success
            CommandStatus.FAILED -> R.string.command_status_failed
            CommandStatus.TIMEOUT -> R.string.command_status_timeout
            CommandStatus.CONNECTION_LOST -> R.string.command_status_connection_lost
        }
    )

    fun commandFeedback(context: Context, command: String?, status: CommandStatus): String {
        val name = commandName(context, command)
        return context.getString(
            when (status) {
                CommandStatus.SUCCESS -> R.string.command_feedback_success
                CommandStatus.FAILED -> R.string.command_feedback_failed
                CommandStatus.TIMEOUT -> R.string.command_feedback_timeout
                CommandStatus.CONNECTION_LOST -> R.string.command_feedback_connection_lost
                CommandStatus.SENDING -> R.string.command_feedback_sending
                CommandStatus.IDLE -> R.string.value_unavailable
            },
            name
        )
    }

    fun productType(context: Context, value: String?): String = when (value) {
        "crawler" -> context.getString(R.string.product_type_crawler)
        null -> context.getString(R.string.value_unavailable)
        else -> context.getString(R.string.product_type_other)
    }

    fun mapHeading(valueCode: Int?, valueName: String?): String = when (valueCode ?: headingCodeFromName(valueName)) {
        0 -> "沿板块横向正向"
        1 -> "沿板块横向反向"
        2 -> "沿板块纵向正向"
        3 -> "沿板块纵向反向"
        else -> "--"
    }

    fun taskKind(value: String?): String = when (value) {
        "coverage" -> "覆盖清扫"
        "return_to_charge" -> "返回充电"
        null, "" -> "--"
        else -> value
    }

    fun orchestrationState(value: String?): String = when (value) {
        "idle" -> "空闲"
        "running" -> "正在执行任务"
        "paused_by_user" -> "已暂停"
        "paused_by_safety" -> "因安全保护已暂停"
        "running_child" -> "正在执行内部动作"
        "resuming" -> "正在恢复任务"
        "succeeded" -> "任务已完成"
        "failed" -> "任务失败"
        "canceled" -> "任务已取消"
        "unknown" -> "任务状态未知"
        null, "" -> "--"
        else -> value
    }

    fun runState(value: String?): String = when (value) {
        "idle" -> "空闲"
        "starting" -> "正在启动"
        "running" -> "运行中"
        "paused" -> "已暂停"
        "succeeded" -> "已完成"
        "failed" -> "执行失败"
        "canceled" -> "已取消"
        "unknown" -> "状态未知"
        null, "" -> "暂无任务"
        else -> "状态未知"
    }

    fun safetyState(value: String?): String = when (value) {
        "normal" -> "正常"
        "low_battery" -> "低电量保护"
        "fault" -> "设备故障"
        "estop" -> "急停中"
        "clearing_estop" -> "正在解除急停"
        "unknown" -> "状态未知"
        null, "" -> "暂无数据"
        else -> "状态未知"
    }

    fun missionPhase(value: String?): String = when (value) {
        "none" -> "未开始"
        "waiting_for_robot" -> "等待机器人响应"
        "resolving_start" -> "确认任务起点"
        "planning" -> "正在规划路径"
        "executing" -> "正在执行"
        "placeholder" -> "准备中"
        "unknown" -> "阶段未知"
        null, "" -> "暂无阶段信息"
        else -> "阶段未知"
    }

    fun activeAction(value: String?): String = when (value?.lowercase()) {
        "starting" -> "启动任务"
        "cross_panel" -> "跨板移动"
        null, "" -> "暂无动作"
        else -> "执行机器人动作"
    }

    fun interruptionReason(value: String?): String = when (value) {
        "LOW_BATTERY" -> "低电量"
        null, "" -> "--"
        else -> value
    }

    fun jobStatus(context: Context, value: String?): String = context.getString(
        when (value) {
            "pending", "queued" -> R.string.job_status_pending
            "running", "in_progress" -> R.string.job_status_running
            "completed", "success", "succeeded" -> R.string.job_status_completed
            "failed", "fault" -> R.string.job_status_failed
            "cancelled", "canceled" -> R.string.job_status_cancelled
            null -> R.string.value_unavailable
            else -> R.string.status_unknown
        }
    )

    private fun headingCodeFromName(value: String?): Int? = when (value) {
        "block_u_positive" -> 0
        "block_u_negative" -> 1
        "block_v_positive" -> 2
        "block_v_negative" -> 3
        else -> null
    }
}
