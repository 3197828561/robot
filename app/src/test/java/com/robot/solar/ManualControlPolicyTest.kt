package com.robot.solar

import com.robot.solar.ui.main.ManualDirection
import com.robot.solar.viewmodel.ManualControlPolicy
import com.robot.solar.viewmodel.MissionControlPolicy
import com.robot.solar.viewmodel.MissionStatusDisplay
import com.robot.solar.network.mqtt.MissionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualControlPolicyTest {
    @Test
    fun directions_useSecondVersionFixedSpeeds() {
        assertEquals(20.0, ManualDirection.FORWARD.linearSpeedCms, 0.0)
        assertEquals(-20.0, ManualDirection.BACKWARD.linearSpeedCms, 0.0)
        assertEquals(0.5, ManualDirection.LEFT.angularSpeedRadps, 0.0)
        assertEquals(-0.5, ManualDirection.RIGHT.angularSpeedRadps, 0.0)
        ManualDirection.entries.forEach { direction ->
            assertTrue(direction.linearSpeedCms == 0.0 || direction.angularSpeedRadps == 0.0)
        }
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
}
