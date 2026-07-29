package com.robot.solar

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.robot.solar.network.mqtt.CommandPayloadFactory
import com.robot.solar.network.mqtt.StatusMessage
import com.robot.solar.network.mqtt.PoseMessage
import com.robot.solar.network.mqtt.CoverageCommandParams
import com.robot.solar.network.mqtt.CoverageStart
import com.robot.solar.network.mqtt.RemoteControlContract
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

    @Test
    fun parseStatus_readsMissionCommandStateSeparatelyFromAck() {
        val status = gson.fromJson(
            """
            {
              "version": "1.0",
              "deviceId": "crawler_00000001",
              "productType": "crawler",
              "missionId": "mission-42",
              "taskKind": "coverage",
              "runState": "running",
              "operationalMode": "auto",
              "safetyState": "normal",
              "phase": "executing",
              "activeAction": "cross_panel",
              "waypointIndex": 3,
              "waypointCount": 9,
              "errorCode": 17,
              "errorRetryable": true,
              "errorSource": "mission_planner",
              "errorMessage": "temporary planning failure"
            }
            """.trimIndent(),
            StatusMessage::class.java
        )

        assertEquals("mission-42", status.missionId)
        assertEquals("running", status.runState)
        assertEquals("auto", status.operationalMode)
        assertEquals("normal", status.safetyState)
        assertEquals("executing", status.phase)
        assertEquals("cross_panel", status.activeAction)
        assertEquals(3, status.waypointIndex)
        assertEquals(9, status.waypointCount)
        assertEquals(17, status.missionErrorCode)
        assertEquals(true, status.errorRetryable)
        assertEquals("mission_planner", status.errorSource)
        assertEquals("temporary planning failure", status.errorMessage)
    }

    @Test
    fun coverageCommandParams_useCurrentPoseAndUniqueTargetBlocks() {
        val params = CoverageCommandParams(
            mapId = 7,
            mapVersion = 3,
            useCurrentPose = true,
            targetBlockIds = listOf(2, 4),
            globalPlan = true
        )
        val json = gson.toJson(params)

        assertEquals(
            """{"mapId":7,"mapVersion":3,"useCurrentPose":true,"targetBlockIds":[2,4],"globalPlan":true}""",
            json
        )
    }

    @Test
    fun preparedCoverageCommand_matchesRobotContractAndIsStableForRetry() {
        val coverage = CoverageCommandParams(
            mapId = 7,
            mapVersion = 3,
            useCurrentPose = true,
            targetBlockIds = listOf(2, 4),
            globalPlan = true
        )
        val prepared = CommandPayloadFactory.create(
            version = "1.0",
            cmdId = "cmd_start_000001",
            deviceId = "crawler_00000001",
            productType = "crawler",
            timestamp = "2026-07-26T08:30:00.123Z",
            cmd = "start",
            params = mapOf("taskKind" to "coverage", "coverage" to coverage),
            gson = gson
        )
        val payload = JsonParser.parseString(prepared.payload).asJsonObject

        assertEquals("cmd_start_000001", prepared.cmdId)
        assertEquals("start", payload["cmd"].asString)
        assertEquals("coverage", payload["params"].asJsonObject["taskKind"].asString)
        assertEquals(7L, payload["params"].asJsonObject["coverage"].asJsonObject["mapId"].asLong)
        assertEquals(true, payload["params"].asJsonObject["coverage"].asJsonObject["useCurrentPose"].asBoolean)
        val retryPayload = prepared.payload
        assertEquals(retryPayload, prepared.payload)
    }

    @Test
    fun coverageCommandParams_includeExplicitStartWhenCurrentPoseIsDisabled() {
        val params = CoverageCommandParams(
            mapId = 7,
            mapVersion = 3,
            useCurrentPose = false,
            start = CoverageStart(
                blockId = 2,
                cellRow = 4,
                cellCol = 5,
                innerRow = 0,
                innerCol = 1,
                heading = 3
            ),
            targetBlockIds = listOf(2, 4),
            globalPlan = true
        )
        val coverage = JsonParser.parseString(gson.toJson(params)).asJsonObject
        val start = coverage["start"].asJsonObject

        assertEquals(false, coverage["useCurrentPose"].asBoolean)
        assertEquals(2L, start["blockId"].asLong)
        assertEquals(4, start["cellRow"].asInt)
        assertEquals(5, start["cellCol"].asInt)
        assertEquals(0, start["innerRow"].asInt)
        assertEquals(1, start["innerCol"].asInt)
        assertEquals(3, start["heading"].asInt)
    }

    @Test
    fun allTargetMissionCommands_containOnlyLatestMissionId() {
        listOf("stop", "pause", "resume", "replan").forEach { command ->
            val prepared = CommandPayloadFactory.create(
                version = "1.0",
                cmdId = "cmd_${command}_000001",
                deviceId = "crawler_00000001",
                productType = "crawler",
                timestamp = "2026-07-26T08:30:00.123Z",
                cmd = command,
                params = mapOf("targetMissionId" to "mission-42"),
                gson = gson
            )
            val params = JsonParser.parseString(prepared.payload).asJsonObject["params"].asJsonObject

            assertEquals(1, params.size())
            assertEquals("mission-42", params["targetMissionId"].asString)
        }
    }

    @Test
    fun modeAndSafetyCommands_sendExactlyEmptyParams() {
        listOf("manual", "auto", "estop", "clear_estop").forEachIndexed { index, command ->
            val prepared = CommandPayloadFactory.create(
                version = "1.0",
                cmdId = "cmd_empty_$index",
                deviceId = "crawler_00000001",
                productType = "crawler",
                timestamp = "2026-07-26T08:30:00.123Z",
                cmd = command,
                params = emptyMap<String, Any?>(),
                gson = gson
            )
            val payload = JsonParser.parseString(prepared.payload).asJsonObject

            assertEquals(command, payload["cmd"].asString)
            assertEquals(0, payload["params"].asJsonObject.size())
        }
    }

    @Test
    fun coverageCommand_supportsUint32UpperBounds() {
        val params = CoverageCommandParams(
            mapId = 4_294_967_295L,
            mapVersion = 4_294_967_295L,
            useCurrentPose = true,
            targetBlockIds = listOf(1),
            globalPlan = true
        )
        val coverage = JsonParser.parseString(gson.toJson(params)).asJsonObject

        assertEquals(4_294_967_295L, coverage["mapId"].asLong)
        assertEquals(4_294_967_295L, coverage["mapVersion"].asLong)
    }

    @Test
    fun remoteContract_clampsAppSpeedToRobotSignedRanges() {
        assertEquals(50.0, RemoteControlContract.clampLinear(75.0), 0.0)
        assertEquals(-50.0, RemoteControlContract.clampLinear(-75.0), 0.0)
        assertEquals(0.5, RemoteControlContract.clampAngular(1.0), 0.0)
        assertEquals(-0.5, RemoteControlContract.clampAngular(-1.0), 0.0)
    }
}
