package com.robot.solar

import com.robot.solar.ui.main.ManualDirection
import com.robot.solar.viewmodel.ManualSpeedPolicy
import com.robot.solar.viewmodel.ManualSpeedPreset
import com.robot.solar.viewmodel.ManualSpeedSettings
import com.robot.solar.viewmodel.ManualControlPolicy
import com.robot.solar.viewmodel.MissionControlPolicy
import com.robot.solar.viewmodel.MissionCommandErrorDisplay
import com.robot.solar.viewmodel.MissionStatusDisplay
import com.robot.solar.network.mqtt.MissionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualControlPolicyTest {
    @Test
    fun directions_applyConfiguredMagnitudeAndOwnTheSigns() {
        val settings = ManualSpeedSettings(
            linearSpeedCms = 37.0,
            angularSpeedRadps = 0.4
        )

        assertEquals(37.0, ManualSpeedPolicy.velocityFor(ManualDirection.FORWARD, settings).linearSpeedCms, 0.0)
        assertEquals(-37.0, ManualSpeedPolicy.velocityFor(ManualDirection.BACKWARD, settings).linearSpeedCms, 0.0)
        assertEquals(0.4, ManualSpeedPolicy.velocityFor(ManualDirection.LEFT, settings).angularSpeedRadps, 0.0)
        assertEquals(-0.4, ManualSpeedPolicy.velocityFor(ManualDirection.RIGHT, settings).angularSpeedRadps, 0.0)
        ManualDirection.entries.forEach { direction ->
            val velocity = ManualSpeedPolicy.velocityFor(direction, settings)
            assertTrue(velocity.linearSpeedCms == 0.0 || velocity.angularSpeedRadps == 0.0)
        }
    }

    @Test
    fun manualSpeed_defaultsPresetsAndBounds_matchConfirmedUiContract() {
        assertEquals(ManualSpeedSettings(30.0, 0.3), ManualSpeedPolicy.normalize(ManualSpeedSettings()))
        assertEquals(
            ManualSpeedSettings(10.0, 0.1),
            ManualSpeedPolicy.fromPreset(ManualSpeedPreset.SLOW)
        )
        assertEquals(
            ManualSpeedSettings(30.0, 0.3),
            ManualSpeedPolicy.fromPreset(ManualSpeedPreset.STANDARD)
        )
        assertEquals(
            ManualSpeedSettings(50.0, 0.5),
            ManualSpeedPolicy.fromPreset(ManualSpeedPreset.HIGH)
        )
        assertEquals(
            ManualSpeedSettings(50.0, 0.5),
            ManualSpeedPolicy.normalize(ManualSpeedSettings(99.0, 2.0))
        )
        assertEquals(
            ManualSpeedSettings(0.0, 0.0),
            ManualSpeedPolicy.normalize(ManualSpeedSettings(-1.0, -0.1))
        )
    }

    @Test
    fun manualSpeed_normalizesUiStepsAndDetectsOnlyExactPresets() {
        assertEquals(
            ManualSpeedSettings(31.0, 0.3),
            ManualSpeedPolicy.normalize(ManualSpeedSettings(30.6, 0.26))
        )
        assertEquals(
            ManualSpeedPreset.STANDARD,
            ManualSpeedPolicy.presetFor(ManualSpeedSettings(30.0, 0.3))
        )
        assertEquals(null, ManualSpeedPolicy.presetFor(ManualSpeedSettings(31.0, 0.3)))
    }

    @Test
    fun allowed_requiresConnectionOnlineManualModeAndNormalSafety() {
        assertTrue(ManualControlPolicy.isAllowed(true, true, "manual", "normal", true))
        assertFalse(ManualControlPolicy.isAllowed(false, true, "manual", "normal", true))
        assertFalse(ManualControlPolicy.isAllowed(true, false, "manual", "normal", true))
        assertFalse(ManualControlPolicy.isAllowed(true, true, "auto", "normal", true))
        assertFalse(ManualControlPolicy.isAllowed(true, true, "manual", "estop", true))
        assertFalse(ManualControlPolicy.isAllowed(true, true, "manual", "normal", false))
    }

    @Test
    fun missionButtons_followRobotRunModeAndSafetyState() {
        val idle = MissionControlPolicy.compute(
            connected = true,
            online = true,
            mission = MissionState(
                runState = "idle",
                operationalMode = "auto",
                safetyState = "normal"
            ),
            manualCommandAccepted = false,
            awaitingStartStatus = false,
            awaitingClearEstopStatus = false,
            commandInFlight = false,
            retryAvailable = false
        )
        assertTrue(idle.canStart)
        assertTrue(idle.canManual)
        assertFalse(idle.canStop)

        val running = MissionControlPolicy.compute(
            connected = true,
            online = true,
            mission = MissionState(
                missionId = "mission-42",
                taskKind = "coverage",
                runState = "running",
                operationalMode = "auto",
                safetyState = "normal"
            ),
            manualCommandAccepted = false,
            awaitingStartStatus = false,
            awaitingClearEstopStatus = false,
            commandInFlight = false,
            retryAvailable = false
        )
        assertFalse(running.canStart)
        assertTrue(running.canStop)
        assertTrue(running.canPause)
        assertTrue(running.canReplan)
        assertFalse(running.canResume)

        val paused = MissionControlPolicy.compute(
            connected = true,
            online = true,
            mission = MissionState(
                missionId = "mission-42",
                taskKind = "coverage",
                runState = "paused",
                operationalMode = "auto",
                safetyState = "normal"
            ),
            manualCommandAccepted = false,
            awaitingStartStatus = false,
            awaitingClearEstopStatus = false,
            commandInFlight = false,
            retryAvailable = true
        )
        assertTrue(paused.canResume)
        assertTrue(paused.canRetry)

        val staleRunStateWithoutMissionId = MissionControlPolicy.compute(
            connected = true,
            online = true,
            mission = MissionState(
                runState = "running",
                operationalMode = "auto",
                safetyState = "normal"
            ),
            manualCommandAccepted = false,
            awaitingStartStatus = false,
            awaitingClearEstopStatus = false,
            commandInFlight = false,
            retryAvailable = false
        )
        assertFalse(staleRunStateWithoutMissionId.canStop)
        assertFalse(staleRunStateWithoutMissionId.canPause)
        assertFalse(staleRunStateWithoutMissionId.canResume)
        assertFalse(staleRunStateWithoutMissionId.canReplan)
    }

    @Test
    fun remoteAndClearEstop_buttons_waitForFinalStatus() {
        val manualPendingAck = MissionControlPolicy.compute(
            connected = true,
            online = true,
            mission = MissionState(
                operationalMode = "manual",
                safetyState = "normal"
            ),
            manualCommandAccepted = false,
            awaitingStartStatus = false,
            awaitingClearEstopStatus = false,
            commandInFlight = false,
            retryAvailable = false
        )
        assertFalse(manualPendingAck.canRemote)

        val manualReady = MissionControlPolicy.compute(
            connected = true,
            online = true,
            mission = MissionState(
                operationalMode = "manual",
                safetyState = "normal"
            ),
            manualCommandAccepted = true,
            awaitingStartStatus = false,
            awaitingClearEstopStatus = false,
            commandInFlight = false,
            retryAvailable = false
        )
        assertTrue(manualReady.canRemote)
        assertTrue(manualReady.canAuto)

        val clearing = MissionControlPolicy.compute(
            connected = true,
            online = true,
            mission = MissionState(
                operationalMode = "auto",
                safetyState = "estop"
            ),
            manualCommandAccepted = false,
            awaitingStartStatus = false,
            awaitingClearEstopStatus = true,
            commandInFlight = false,
            retryAvailable = false
        )
        assertFalse(clearing.canClearEstop)
        assertFalse(clearing.canEstop)
    }

    @Test
    fun missionStatus_safetyAlwaysOverridesStartWaitingState() {
        assertEquals(
            "急停",
            MissionStatusDisplay.text(
                runState = "idle",
                safetyState = "estop",
                awaitingStart = true,
                awaitingClearEstop = false
            )
        )
        assertEquals(
            "故障",
            MissionStatusDisplay.text(
                runState = "running",
                safetyState = "fault",
                awaitingStart = true,
                awaitingClearEstop = false
            )
        )
        assertEquals(
            "低电量",
            MissionStatusDisplay.text(
                runState = "failed",
                safetyState = "low_battery",
                awaitingStart = false,
                awaitingClearEstop = false
            )
        )
    }

    @Test
    fun missionStatus_showsAcceptedIntermediateStatesWithoutClaimingCompletion() {
        assertEquals(
            "启动请求已受理，等待任务状态",
            MissionStatusDisplay.text(
                runState = "idle",
                safetyState = "normal",
                awaitingStart = true,
                awaitingClearEstop = false
            )
        )
        assertEquals(
            "解除急停请求已受理，等待安全状态更新",
            MissionStatusDisplay.text(
                runState = "idle",
                safetyState = "estop",
                awaitingStart = false,
                awaitingClearEstop = true
            )
        )
    }

    @Test
    fun missionCommandErrors_haveExplicitServiceAndRejectionMessages() {
        assertEquals(
            "任务服务不可用（MISSION_SERVICE_UNAVAILABLE）",
            MissionCommandErrorDisplay.text("MISSION_SERVICE_UNAVAILABLE")
        )
        assertEquals(
            "任务服务响应超时（MISSION_SERVICE_TIMEOUT）",
            MissionCommandErrorDisplay.text("MISSION_SERVICE_TIMEOUT")
        )
        assertEquals(
            "任务层拒绝了该操作（MISSION_REJECTED）",
            MissionCommandErrorDisplay.text("MISSION_REJECTED")
        )
        listOf(
            "INVALID_PAYLOAD",
            "UNSUPPORTED_VERSION",
            "DEVICE_MISMATCH",
            "UNSUPPORTED_CMD",
            "MISSION_SERVICE_UNAVAILABLE",
            "MISSION_SERVICE_TIMEOUT",
            "MISSION_SERVICE_ERROR",
            "MISSION_INVALID_COMMAND",
            "MISSION_INVALID_REQUEST",
            "MISSION_BUSY",
            "MISSION_NOT_FOUND",
            "MISSION_ILLEGAL_STATE",
            "MISSION_INTERNAL_ERROR",
            "MISSION_REJECTED"
        ).forEach { errorCode ->
            val display = MissionCommandErrorDisplay.text(errorCode)
            assertTrue("Missing display for $errorCode", !display.isNullOrBlank())
            assertTrue("Display must retain $errorCode", display!!.contains(errorCode))
        }
    }
}
