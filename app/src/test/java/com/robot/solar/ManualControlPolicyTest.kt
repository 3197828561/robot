package com.robot.solar

import com.robot.solar.network.mqtt.StatusMessage
import com.robot.solar.ui.main.ManualDirection
import com.robot.solar.viewmodel.ManualControlPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualControlPolicyTest {
    @Test
    fun directions_useSecondVersionFixedSpeeds() {
        assertEquals(20.0, ManualDirection.FORWARD.linearSpeedCms, 0.0)
        assertEquals(-20.0, ManualDirection.BACKWARD.linearSpeedCms, 0.0)
        assertEquals(-0.5, ManualDirection.LEFT.angularSpeedRadps, 0.0)
        assertEquals(0.5, ManualDirection.RIGHT.angularSpeedRadps, 0.0)
        ManualDirection.entries.forEach { direction ->
            assertTrue(direction.linearSpeedCms == 0.0 || direction.angularSpeedRadps == 0.0)
        }
    }

    @Test
    fun allowed_requiresConnectedOnlineStoppedManualAndNormal() {
        assertTrue(ManualControlPolicy.isAllowed(true, true, validStatus))
        assertFalse(ManualControlPolicy.isAllowed(false, true, validStatus))
        assertFalse(ManualControlPolicy.isAllowed(true, false, validStatus))
        assertFalse(ManualControlPolicy.isAllowed(true, true, validStatus.copy(workStatus = "running")))
        assertFalse(ManualControlPolicy.isAllowed(true, true, validStatus.copy(controlMode = "auto")))
        assertFalse(ManualControlPolicy.isAllowed(true, true, validStatus.copy(deviceStatus = "warning")))
        assertFalse(ManualControlPolicy.isAllowed(true, true, validStatus.copy(deviceStatus = "fault")))
        assertFalse(ManualControlPolicy.isAllowed(true, true, null))
    }

    private val validStatus = StatusMessage(
        version = "1.0",
        deviceId = "crawler_1",
        productType = "crawler",
        timestamp = null,
        workStatus = "stopped",
        controlMode = "manual",
        batteryPercent = 80.0,
        linearSpeedCms = 0.0,
        angularSpeedRadps = 0.0,
        deviceStatus = "normal",
        movementStatus = "stopped",
        yawDeg = null,
        pitchDeg = null,
        temperatureC = null,
        totalMileageM = null,
        cleanedRows = null,
        pressureKpa = null,
        antiFallLeftM = null,
        antiFallRightM = null
    )
}
