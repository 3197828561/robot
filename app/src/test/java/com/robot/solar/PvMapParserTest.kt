package com.robot.solar

import com.robot.solar.map.MapValidationException
import com.robot.solar.map.PvMapParser
import com.robot.solar.network.mqtt.PoseMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class PvMapParserTest {
    private val parser = PvMapParser()

    @Test
    fun parseAndResolvePose_usesCellPolygonAndBlockHeading() {
        val map = parser.parse(validMap)
        val pose = PoseMessage(
            "1.0", "crawler_1", "crawler", null,
            7, 3, 1, 10, 0, 0, 1, 1, 0, "block_u_positive"
        )

        val position = parser.resolvePose(map, pose)

        assertNotNull(position)
        assertEquals(75.0, position!!.point.u, 0.001)
        assertEquals(75.0, position.point.v, 0.001)
        assertEquals(90.0, position.headingDegrees, 0.001)
    }

    @Test
    fun resolvePose_rejectsDifferentMapVersion() {
        val map = parser.parse(validMap)
        val pose = PoseMessage("1.0", "crawler_1", "crawler", null, 7, 4, 1, 10, 0, 0, 0, 0, 0, null)
        assertNull(parser.resolvePose(map, pose))
    }

    @Test(expected = MapValidationException::class)
    fun parse_rejectsCellInMissingGridPosition() {
        parser.parse(validMap.replace("\"grid\":[[1]]", "\"grid\":[[0]]"))
    }

    @Test
    fun parseRepositoryComplexExample_readsCompleteMap() {
        val file = sequenceOf(
            File("docs/requirements/map_planner/config/example_map_complex.json"),
            File("../docs/requirements/map_planner/config/example_map_complex.json")
        ).firstOrNull(File::isFile) ?: error("找不到 map_planner 示例地图")

        val map = parser.parse(file)

        assertEquals(2L, map.mapId)
        assertEquals(5, map.blocks.size)
        assertEquals(26, map.cells.size)
        assertEquals(4, map.bridges.size)
    }

    private val validMap = """
        {
          "map_id":7,
          "version":3,
          "frame":{"unit":"centimeter"},
          "cell_model":{"inner_rows":2,"inner_cols":2},
          "blocks":[{
            "block_id":1,
            "block_frame":{"block_origin":[0,0],"u_axis":[0,1],"v_axis":[-1,0]},
            "rows":1,"cols":1,"grid":[[1]],"cell_ids":[10],"cleanable":true
          }],
          "bridges":[],
          "cells":[{
            "cell_id":10,"block_id":1,"row":0,"col":0,
            "polygon":[[0,0],[100,0],[100,100],[0,100]]
          }]
        }
    """.trimIndent()
}
