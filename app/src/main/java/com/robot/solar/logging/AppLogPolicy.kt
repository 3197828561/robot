package com.robot.solar.logging

import com.robot.solar.entity.LogCategory
import com.robot.solar.entity.LogFilter
import com.robot.solar.entity.LogSeverity
import com.robot.solar.entity.StructuredLogEntity

data class StatusLogSnapshot(
    val missionId: String?,
    val runState: String?,
    val operationalMode: String?,
    val safetyState: String?,
    val deviceStatus: String?,
    val movementStatus: String?,
    val batteryPercent: Int?,
    val errorCode: Int?,
    val errorMessage: String?,
    val rootMissionId: String? = null,
    val taskKind: String? = null,
    val orchestrationState: String? = null,
    val taskStackDepth: Int? = null,
    val interruptionReason: String? = null
)

data class StatusLogChange(
    val category: LogCategory,
    val eventType: String,
    val severity: LogSeverity,
    val summary: String,
    val result: String? = null
)

object AppLogPolicy {
    fun heartbeatSummary(previousOnline: Boolean?, online: Boolean): String? = when {
        previousOnline == null && online -> "设备已上线"
        previousOnline == null && !online -> null
        previousOnline == true && !online -> "设备已离线"
        previousOnline == false && online -> "设备重新上线"
        else -> null
    }

    fun statusChanges(
        previous: StatusLogSnapshot?,
        current: StatusLogSnapshot
    ): List<StatusLogChange> {
        if (previous == null) {
            return listOf(
                StatusLogChange(
                    category = LogCategory.DEVICE,
                    eventType = "status_initialized",
                    severity = LogSeverity.INFO,
                    summary = "已同步机器人初始状态"
                )
            )
        }
        return buildList {
            if (
                previous.rootMissionId != current.rootMissionId &&
                !current.rootMissionId.isNullOrBlank()
            ) {
                add(
                    StatusLogChange(
                        LogCategory.TASK,
                        "root_mission_changed",
                        LogSeverity.INFO,
                        "根任务变更为 ${current.rootMissionId}"
                    )
                )
            }
            if (previous.missionId != current.missionId && !current.missionId.isNullOrBlank()) {
                add(
                    StatusLogChange(
                        LogCategory.TASK,
                        "current_task_changed",
                        LogSeverity.INFO,
                        "当前执行任务变更为 ${current.missionId}${current.taskKind?.let { "（$it）" }.orEmpty()}"
                    )
                )
            }
            if (
                previous.orchestrationState != current.orchestrationState &&
                !current.orchestrationState.isNullOrBlank()
            ) {
                val severity = if (current.orchestrationState == "failed") {
                    LogSeverity.ERROR
                } else {
                    LogSeverity.INFO
                }
                add(
                    StatusLogChange(
                        LogCategory.TASK,
                        "orchestration_state_changed",
                        severity,
                        "根任务状态：${previous.orchestrationState ?: "--"} → ${current.orchestrationState}",
                        current.orchestrationState
                    )
                )
            }
            if (
                previous.taskStackDepth != current.taskStackDepth &&
                current.taskStackDepth != null
            ) {
                add(
                    StatusLogChange(
                        LogCategory.TASK,
                        "task_stack_depth_changed",
                        LogSeverity.INFO,
                        "任务栈深度：${previous.taskStackDepth ?: "--"} → ${current.taskStackDepth}",
                        current.taskStackDepth.toString()
                    )
                )
            }
            if (
                previous.interruptionReason != current.interruptionReason &&
                !current.interruptionReason.isNullOrBlank()
            ) {
                add(
                    StatusLogChange(
                        LogCategory.TASK,
                        "mission_interrupted",
                        LogSeverity.WARNING,
                        "根任务被打断：${current.interruptionReason}",
                        current.interruptionReason
                    )
                )
            }
            if (previous.runState != current.runState && !current.runState.isNullOrBlank()) {
                val severity = if (current.runState == "failed") LogSeverity.ERROR else LogSeverity.INFO
                add(
                    StatusLogChange(
                        LogCategory.TASK,
                        "run_state_changed",
                        severity,
                        "当前任务状态：${previous.runState ?: "--"} → ${current.runState}",
                        current.runState
                    )
                )
            }
            if (
                previous.operationalMode != current.operationalMode &&
                !current.operationalMode.isNullOrBlank()
            ) {
                add(
                    StatusLogChange(
                        LogCategory.DEVICE,
                        "operational_mode_changed",
                        LogSeverity.INFO,
                        "运行模式：${previous.operationalMode ?: "--"} → ${current.operationalMode}",
                        current.operationalMode
                    )
                )
            }
            if (previous.safetyState != current.safetyState && !current.safetyState.isNullOrBlank()) {
                val severity =
                    if (current.safetyState == "normal") LogSeverity.INFO else LogSeverity.CRITICAL
                add(
                    StatusLogChange(
                        LogCategory.SAFETY,
                        "safety_state_changed",
                        severity,
                        "安全状态：${previous.safetyState ?: "--"} → ${current.safetyState}",
                        current.safetyState
                    )
                )
            }
            if (
                previous.movementStatus != current.movementStatus &&
                !current.movementStatus.isNullOrBlank()
            ) {
                add(
                    StatusLogChange(
                        LogCategory.DEVICE,
                        "movement_changed",
                        LogSeverity.INFO,
                        "机器人运动状态：${previous.movementStatus ?: "--"} → ${current.movementStatus}",
                        current.movementStatus
                    )
                )
            }
            if (
                previous.deviceStatus != current.deviceStatus &&
                !current.deviceStatus.isNullOrBlank() &&
                current.deviceStatus != "normal"
            ) {
                add(
                    StatusLogChange(
                        LogCategory.DEVICE,
                        "device_status_changed",
                        LogSeverity.WARNING,
                        "设备状态变为 ${current.deviceStatus}",
                        current.deviceStatus
                    )
                )
            }
            if (
                previous.batteryPercent?.let { it > LOW_BATTERY_PERCENT } != false &&
                current.batteryPercent?.let { it <= LOW_BATTERY_PERCENT } == true
            ) {
                add(
                    StatusLogChange(
                        LogCategory.SAFETY,
                        "low_battery",
                        LogSeverity.WARNING,
                        "设备电量降至 ${current.batteryPercent}%"
                    )
                )
            }
            if (
                (previous.errorCode != current.errorCode || previous.errorMessage != current.errorMessage) &&
                (current.errorCode != null && current.errorCode != 0 || !current.errorMessage.isNullOrBlank())
            ) {
                add(
                    StatusLogChange(
                        LogCategory.TASK,
                        "mission_error",
                        LogSeverity.ERROR,
                        "任务错误 ${current.errorCode ?: "--"}：${current.errorMessage.orEmpty()}".trimEnd('：')
                    )
                )
            }
        }
    }

    fun matchesFilter(item: StructuredLogEntity, filter: LogFilter): Boolean = when (filter) {
        LogFilter.ALL -> true
        LogFilter.OPERATIONS -> item.category in setOf(
            LogCategory.AUTH,
            LogCategory.COMMAND,
            LogCategory.REMOTE
        )
        LogFilter.DEVICE -> item.category in setOf(
            LogCategory.DEVICE,
            LogCategory.TASK,
            LogCategory.SAFETY,
            LogCategory.MAP
        )
        LogFilter.CONNECTION -> item.category == LogCategory.CONNECTION
        LogFilter.ERRORS -> item.severity in setOf(
            LogSeverity.WARNING,
            LogSeverity.ERROR,
            LogSeverity.CRITICAL
        )
    }

    fun matchesQuery(item: StructuredLogEntity, rawQuery: String): Boolean {
        val query = rawQuery.trim()
        if (query.isEmpty()) return true
        return listOfNotNull(
            item.summary,
            item.deviceId,
            item.cmdId,
            item.missionId,
            item.action,
            item.result,
            item.topic,
            item.detailJson
        ).any { it.contains(query, ignoreCase = true) }
    }

    private const val LOW_BATTERY_PERCENT = 20
}
