package com.robot.solar

import com.google.gson.Gson
import com.robot.solar.network.mqtt.StatusMessage
import com.robot.solar.network.mqtt.PoseMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class MqttModelsParseTest {

    private val gson = Gson()

    @Test
    fun parseStatus_usesSecondVersionDeviceFieldsOnly() {
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
    }

    @Test
    fun parsePose_readsDiscreteMapPosition() {
        val pose = gson.fromJson(
            """
            {
              "version": "1.0",
              "deviceId": "crawler_00000001",
              "productType": "crawler",
              "timestamp": "2026-07-08T07:51:00.123Z",
              "mapId": 2,
              "mapVersion": 1,
              "blockId": 3,
              "cellId": 16,
              "cellRow": 0,
              "cellCol": 1,
              "innerRow": 1,
              "innerCol": 4,
              "headingCode": 0,
              "heading": "block_u_positive"
            }
            """.trimIndent(),
            PoseMessage::class.java
        )

        assertEquals(2L, pose.mapId)
        assertEquals(3L, pose.blockId)
        assertEquals(16L, pose.cellId)
        assertEquals(4, pose.innerCol)
        assertEquals(0, pose.headingCode)
    }
}
