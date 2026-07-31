package com.robot.solar

import com.robot.solar.network.mqtt.MissionCommandPayloads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MissionCommandPayloadsTest {

    @Test
    fun coverageStart_buildsExactCurrentPosePayload() {
        val params = MissionCommandPayloads.coverageStart(
            mapId = 7,
            mapVersion = 3,
            cleanableBlockIds = listOf(4, 2, 4, -1)
        )!!

        assertEquals("coverage", params["taskKind"])
        @Suppress("UNCHECKED_CAST")
        val coverage = params["coverage"] as Map<String, Any?>
        assertEquals(7L, coverage["mapId"])
        assertEquals(3, coverage["mapVersion"])
        assertEquals(true, coverage["useCurrentPose"])
        assertEquals(listOf(2L, 4L), coverage["targetBlockIds"])
        assertEquals(true, coverage["globalPlan"])
        assertEquals(false, coverage.containsKey("start"))
    }

    @Test
    fun coverageStart_rejectsInvalidIdsAndEmptyTargets() {
        assertNull(MissionCommandPayloads.coverageStart(-1, 1, listOf(2)))
        assertNull(MissionCommandPayloads.coverageStart(4_294_967_296L, 1, listOf(2)))
        assertNull(MissionCommandPayloads.coverageStart(1, -1, listOf(2)))
        assertNull(MissionCommandPayloads.coverageStart(1, 1, listOf(0, -2)))
    }

    @Test
    fun targetMission_usesOnlyNonBlankLatestId() {
        assertEquals(mapOf("targetMissionId" to "mission-42"), MissionCommandPayloads.targetMission("mission-42"))
        assertNull(MissionCommandPayloads.targetMission(""))
        assertNull(MissionCommandPayloads.targetMission(null))
    }
}
