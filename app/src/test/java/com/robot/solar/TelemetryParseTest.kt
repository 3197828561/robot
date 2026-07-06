package com.robot.solar

import com.google.gson.Gson
import com.robot.solar.network.mqtt.TelemetryMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TelemetryParseTest {

    private val gson = Gson()

    @Test
    fun parseIdleTelemetry_includesIntegrationFields() {
        val json = javaClass.classLoader
            ?.getResourceAsStream("telemetry-idle.json")
            ?.bufferedReader()
            ?.readText()
            ?: error("missing test resource")

        val msg = gson.fromJson(json, TelemetryMessage::class.java)
        val status = requireNotNull(msg.status)

        assertEquals(82.5, status.batteryPercent!!, 0.01)
        assertEquals(42.0, status.temperatureC!!, 0.01)
        assertEquals(12856.0, status.totalMileageM!!, 0.01)
        assertEquals(156, status.cleanedRows)
        assertEquals(101.3, status.pressureKpa!!, 0.01)
        assertEquals(2.10, status.antiFallLeftM!!, 0.01)
        assertEquals(2.05, status.antiFallRightM!!, 0.01)
        assertEquals(0, status.motionState)
        assertNotNull(msg.navStatus)
    }
}
