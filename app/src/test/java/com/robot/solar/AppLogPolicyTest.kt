package com.robot.solar

import com.robot.solar.entity.LogCategory
import com.robot.solar.entity.LogDirection
import com.robot.solar.entity.LogFilter
import com.robot.solar.entity.LogSeverity
import com.robot.solar.entity.LogSource
import com.robot.solar.entity.StructuredLogEntity
import com.robot.solar.logging.AppLogPolicy
import com.robot.solar.logging.StatusLogSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogPolicyTest {
    @Test
    fun heartbeat_recordsTransitionsInsteadOfEveryFrame() {
        assertEquals("设备已上线", AppLogPolicy.heartbeatSummary(null, true))
        assertNull(AppLogPolicy.heartbeatSummary(true, true))
        assertEquals("设备已离线", AppLogPolicy.heartbeatSummary(true, false))
        assertNull(AppLogPolicy.heartbeatSummary(false, false))
        assertEquals("设备重新上线", AppLogPolicy.heartbeatSummary(false, true))
        assertNull(AppLogPolicy.heartbeatSummary(null, false))
    }

    @Test
    fun identicalStatus_doesNotCreateRepeatedLogs() {
        val snapshot = status()

        assertTrue(AppLogPolicy.statusChanges(snapshot, snapshot).isEmpty())
    }

    @Test
    fun statusTransitions_areClassifiedAndGivenUsefulSeverity() {
        val changes = AppLogPolicy.statusChanges(
            status(),
            status(
                missionId = "mission-2",
                rootMissionId = "mission-root-2",
                taskKind = "return_to_charge",
                runState = "failed",
                orchestrationState = "failed",
                taskStackDepth = 2,
                interruptionReason = "LOW_BATTERY",
                operationalMode = "manual",
                safetyState = "estop",
                deviceStatus = "fault",
                movementStatus = "stopped",
                batteryPercent = 20,
                errorCode = 42,
                errorMessage = "motor fault"
            )
        )

        assertTrue(changes.any { it.eventType == "root_mission_changed" && it.category == LogCategory.TASK })
        assertTrue(changes.any { it.eventType == "current_task_changed" && it.category == LogCategory.TASK })
        assertTrue(changes.any { it.eventType == "orchestration_state_changed" && it.severity == LogSeverity.ERROR })
        assertTrue(changes.any { it.eventType == "task_stack_depth_changed" })
        assertTrue(changes.any { it.eventType == "mission_interrupted" && it.severity == LogSeverity.WARNING })
        assertTrue(changes.any { it.eventType == "run_state_changed" && it.severity == LogSeverity.ERROR })
        assertTrue(changes.any { it.eventType == "operational_mode_changed" })
        assertTrue(changes.any { it.eventType == "safety_state_changed" && it.severity == LogSeverity.CRITICAL })
        assertTrue(changes.any { it.eventType == "device_status_changed" && it.severity == LogSeverity.WARNING })
        assertTrue(changes.any { it.eventType == "low_battery" })
        assertTrue(changes.any { it.eventType == "mission_error" })
    }

    @Test
    fun filterAndSearch_coverOperationalConnectionAndErrorViews() {
        val command = log(
            category = LogCategory.COMMAND,
            severity = LogSeverity.INFO,
            summary = "启动任务成功",
            cmdId = "cmd-100"
        )
        val connectionError = log(
            category = LogCategory.CONNECTION,
            severity = LogSeverity.ERROR,
            summary = "MQTT 连接失败"
        )

        assertTrue(AppLogPolicy.matchesFilter(command, LogFilter.OPERATIONS))
        assertFalse(AppLogPolicy.matchesFilter(command, LogFilter.CONNECTION))
        assertTrue(AppLogPolicy.matchesFilter(connectionError, LogFilter.CONNECTION))
        assertTrue(AppLogPolicy.matchesFilter(connectionError, LogFilter.ERRORS))
        assertTrue(AppLogPolicy.matchesQuery(command, "CMD-100"))
        assertTrue(AppLogPolicy.matchesQuery(connectionError, "mqtt"))
        assertFalse(AppLogPolicy.matchesQuery(command, "不存在"))
    }

    private fun status(
        missionId: String? = "mission-1",
        runState: String? = "running",
        operationalMode: String? = "auto",
        safetyState: String? = "normal",
        deviceStatus: String? = "normal",
        movementStatus: String? = "moving",
        batteryPercent: Int? = 50,
        errorCode: Int? = 0,
        errorMessage: String? = null,
        rootMissionId: String? = "mission-root-1",
        taskKind: String? = "coverage",
        orchestrationState: String? = "running",
        taskStackDepth: Int? = 1,
        interruptionReason: String? = null
    ) = StatusLogSnapshot(
        missionId = missionId,
        runState = runState,
        operationalMode = operationalMode,
        safetyState = safetyState,
        deviceStatus = deviceStatus,
        movementStatus = movementStatus,
        batteryPercent = batteryPercent,
        errorCode = errorCode,
        errorMessage = errorMessage,
        rootMissionId = rootMissionId,
        taskKind = taskKind,
        orchestrationState = orchestrationState,
        taskStackDepth = taskStackDepth,
        interruptionReason = interruptionReason
    )

    private fun log(
        category: LogCategory,
        severity: LogSeverity,
        summary: String,
        cmdId: String? = null
    ) = StructuredLogEntity(
        eventId = "event-$summary",
        timestampMillis = 1L,
        source = LogSource.APP,
        category = category,
        eventType = "test",
        severity = severity,
        direction = LogDirection.LOCAL,
        cmdId = cmdId,
        summary = summary
    )
}
