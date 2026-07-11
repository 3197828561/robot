package com.robot.solar

import com.google.gson.Gson
import com.robot.solar.network.mqtt.StatusMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MqttModelsParseTest {

    private val gson = Gson()

    @Test
    fun parseStatus_allowsMissingPositionFields() {
        val json = """
            {
              "version": "1.0",
              "deviceId": "unit_00000002",
              "productType": "unit",
              "timestamp": "2026-07-08T07:51:00.123Z",
              "workStatus": "stopped",
              "controlMode": "manual",
              "batteryPercent": 82,
              "linearSpeedCms": 0.0,
              "angularSpeedRadps": 0.0,
              "deviceStatus": "normal",
              "movementStatus": "stopped",
              "temperatureC": 42.0,
              "cleanedRows": 156
            }
        """.trimIndent()

        val status = gson.fromJson(json, StatusMessage::class.java)

        assertEquals("stopped", status.workStatus)
        assertEquals(82.0, status.batteryPercent!!, 0.01)
        assertEquals(42.0, status.temperatureC!!, 0.01)
        assertEquals(156, status.cleanedRows)
        assertNull(status.positionX)
        assertNull(status.positionY)
        assertNull(status.headingDeg)
    }
}
