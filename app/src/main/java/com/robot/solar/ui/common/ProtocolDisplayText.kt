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
}
