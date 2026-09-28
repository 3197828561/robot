package com.robot.solar

import com.robot.solar.map.BlockFrame
import com.robot.solar.map.CellModel
import com.robot.solar.map.MapBlock
import com.robot.solar.map.MapCell
import com.robot.solar.map.MapFrame
import com.robot.solar.map.MapRepositoryState
import com.robot.solar.map.MapSyncResult
import com.robot.solar.map.MapSyncSource
import com.robot.solar.map.PvMap
import com.robot.solar.network.http.dto.ActiveMapDto
import com.robot.solar.network.http.dto.CurrentMapResponse
import com.robot.solar.ui.main.MainMapDisplayPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class MainMapDisplayPolicyTest {
    @Test
    fun select_usesHttpMapV2WhenAvailable() {
        val httpMap = pvMap(mapId = 10, version = 2)
        val httpState = MapRepositoryState(
            currentResult = mapSyncResult(httpMap, mapName = "http-map")
        )

        val selected = MainMapDisplayPolicy.select(httpState)

        assertSame(httpMap, selected.pvMap)
        assertEquals(10L, selected.mapId)
        assertEquals(2L, selected.mapVersion)
        assertEquals("http-map", selected.mapName)
    }

    @Test
    fun select_returnsNoMapWhenHttpMapV2IsUnavailable() {
        val selected = MainMapDisplayPolicy.select(MapRepositoryState())

        assertNull(selected.pvMap)
        assertNull(selected.mapId)
        assertEquals("暂无地图", selected.message)
    }

    private fun mapSyncResult(map: PvMap, mapName: String): MapSyncResult {
        val current = CurrentMapResponse(
            productType = "crawler",
            deviceId = "crawler_1",
            activeRevision = 1,
            activeMap = ActiveMapDto(
                mapId = map.mapId,
                mapVersion = map.version,
                mapName = mapName,
                checksum = "sha256:${"0".repeat(64)}",
                fileSizeBytes = 123,
                contentUrl = "https://example.test/map.json"
            ),
            activatedAt = "2026-08-19T00:00:00.000Z",
            lastReportedAt = "2026-08-19T00:00:00.000Z"
        )
        return MapSyncResult(
            current = current,
            pvMap = map,
            cacheFile = File("http-cache.json"),
            source = MapSyncSource.DOWNLOAD
        )
    }

    private fun pvMap(mapId: Long, version: Long): PvMap =
        PvMap(
            mapId = mapId,
            version = version,
            frame = MapFrame(unit = "centimeter"),
            cellModel = CellModel(innerRows = 2, innerCols = 2),
            blocks = listOf(
                MapBlock(
                    blockId = 1,
                    blockFrame = BlockFrame(
                        blockOrigin = listOf(0.0, 0.0),
                        uAxis = listOf(1.0, 0.0),
                        vAxis = listOf(0.0, 1.0)
                    ),
                    rows = 1,
                    cols = 1,
                    grid = listOf(listOf(1)),
                    cellIds = listOf(10)
                )
            ),
            cells = listOf(
                MapCell(
                    cellId = 10,
                    blockId = 1,
                    row = 0,
                    col = 0,
                    polygon = listOf(
                        listOf(0.0, 0.0),
                        listOf(1.0, 0.0),
                        listOf(1.0, 1.0),
                        listOf(0.0, 1.0)
                    )
                )
            )
        )
}
