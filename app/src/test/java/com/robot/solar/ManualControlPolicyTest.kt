package com.robot.solar

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
    fun allowed_requiresConnectedAndOnline() {
        assertTrue(ManualControlPolicy.isAllowed(true, true))
        assertFalse(ManualControlPolicy.isAllowed(false, true))
        assertFalse(ManualControlPolicy.isAllowed(true, false))
        assertFalse(ManualControlPolicy.isAllowed(false, false))
    }
}
