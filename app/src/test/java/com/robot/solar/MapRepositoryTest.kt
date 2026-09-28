package com.robot.solar

import com.robot.solar.map.MapRepository
import com.robot.solar.map.MapSyncManager
import com.robot.solar.map.MapSyncSource
import com.robot.solar.network.http.ApiService
import com.robot.solar.network.http.dto.ActiveMapDto
import com.robot.solar.network.http.dto.CurrentMapResponse
import com.robot.solar.network.http.dto.DeviceDto
import com.robot.solar.network.http.dto.FirmwareDto
import com.robot.solar.network.http.dto.FirmwareUpgradeRequest
import com.robot.solar.network.http.dto.FirmwareUpgradeResponse
import com.robot.solar.network.http.dto.JobDto
import com.robot.solar.network.http.dto.LoginRequest
import com.robot.solar.network.http.dto.LogoutResponse
import com.robot.solar.network.http.dto.MapMetadataDto
import com.robot.solar.network.http.dto.RefreshRequest
import com.robot.solar.network.http.dto.TokenResponse
import com.robot.solar.network.http.dto.WifiConfigDto
import com.robot.solar.network.http.dto.WifiConfigUpdate
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class MapRepositoryTest {
    @Test
    fun syncCurrentMap_callsMapSyncManagerAndReturnsResult() = runBlocking {
        val bytes = validMapBytes(mapId = 2, version = 1)
        val api = RepositoryFakeApiService(
            current = currentFor(bytes, mapId = 2, version = 1),
            contentBytes = bytes
        )
        val repository = MapRepository(MapSyncManager(temporaryDirectory(), api))

        val result = repository.syncCurrentMap("crawler", "crawler_1")

        assertEquals(1, api.currentCalls)
        assertEquals(1, api.contentCalls)
        assertEquals(MapSyncSource.DOWNLOAD, result.source)
        assertEquals(2L, result.pvMap.mapId)
        assertEquals(1L, result.pvMap.version)
    }

    @Test
    fun syncCurrentMap_savesCurrentMapResultInState() = runBlocking {
        val bytes = validMapBytes(mapId = 5, version = 3)
        val repository = MapRepository(
            MapSyncManager(
                temporaryDirectory(),
                RepositoryFakeApiService(current = currentFor(bytes, mapId = 5, version = 3), contentBytes = bytes)
            )
        )

        val result = repository.syncCurrentMap("crawler", "crawler_1")

        assertFalse(repository.state.value.isSyncing)
        assertSame(result, repository.state.value.currentResult)
        assertEquals(null, repository.state.value.error)
        assertEquals(5L, repository.state.value.currentResult!!.pvMap.mapId)
        assertEquals(3L, repository.state.value.currentResult!!.pvMap.version)
    }

    @Test
    fun syncCurrentMap_propagatesExceptionAndSavesErrorState() = runBlocking {
        val failure = IllegalStateException("current failed")
        val repository = MapRepository(
            MapSyncManager(
                temporaryDirectory(),
                RepositoryFakeApiService(currentFailure = failure)
            )
        )

        val failed = runCatching { repository.syncCurrentMap("crawler", "crawler_1") }

        assertSame(failure, failed.exceptionOrNull())
        assertFalse(repository.state.value.isSyncing)
        assertSame(failure, repository.state.value.error)
        assertEquals(null, repository.state.value.currentResult)
    }

    private fun temporaryDirectory(): File =
        Files.createTempDirectory("map-repository-test").toFile().apply { deleteOnExit() }

    private fun currentFor(bytes: ByteArray, mapId: Long, version: Long): CurrentMapResponse =
        CurrentMapResponse(
            productType = "crawler",
            deviceId = "crawler_1",
            activeRevision = 1,
            activeMap = ActiveMapDto(
                mapId = mapId,
                mapVersion = version,
                mapName = "test-map",
                checksum = "sha256:${sha256(bytes)}",
                fileSizeBytes = bytes.size.toLong(),
                contentUrl = "https://example.test/map.json"
            ),
            activatedAt = "2026-08-19T00:00:00.000Z",
            lastReportedAt = "2026-08-19T00:00:00.000Z"
        )

    private fun validMapBytes(mapId: Long, version: Long): ByteArray =
        """
        {
          "map_id": $mapId,
          "version": $version,
          "frame": { "unit": "centimeter" },
          "cell_model": { "inner_rows": 2, "inner_cols": 2 },
          "blocks": [
            {
              "block_id": 1,
              "block_frame": {
                "block_origin": [0.0, 0.0],
                "u_axis": [1.0, 0.0],
                "v_axis": [0.0, 1.0]
              },
              "rows": 1,
              "cols": 1,
              "grid": [[1]],
              "cell_ids": [10],
              "cleanable": true
            }
          ],
          "bridges": [],
          "cells": [
            {
              "cell_id": 10,
              "block_id": 1,
              "row": 0,
              "col": 0,
              "polygon": [[0.0,0.0],[1.0,0.0],[1.0,1.0],[0.0,1.0]]
            }
          ]
        }
        """.trimIndent().toByteArray(Charsets.UTF_8)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}

private class RepositoryFakeApiService(
    private val current: CurrentMapResponse? = null,
    private val contentBytes: ByteArray? = null,
    private val currentFailure: Throwable? = null
) : ApiService {
    var currentCalls: Int = 0
        private set
    var contentCalls: Int = 0
        private set

    override suspend fun login(body: LoginRequest): TokenResponse = error("not used")
    override suspend fun refresh(body: RefreshRequest): TokenResponse = error("not used")
    override suspend fun logout(body: RefreshRequest): LogoutResponse = error("not used")
    override suspend fun listDevices(): List<DeviceDto> = error("not used")
    override suspend fun listJobs(deviceId: String): List<JobDto> = error("not used")
    override suspend fun latestFirmware(deviceId: String): FirmwareDto = error("not used")
    override suspend fun triggerFirmwareUpgrade(body: FirmwareUpgradeRequest): FirmwareUpgradeResponse = error("not used")
    override suspend fun getWifi(deviceId: String): WifiConfigDto = error("not used")
    override suspend fun updateWifi(deviceId: String, body: WifiConfigUpdate): WifiConfigDto = error("not used")

    override suspend fun getCurrentMap(productType: String, deviceId: String): CurrentMapResponse {
        currentCalls += 1
        currentFailure?.let { throw it }
        return current ?: error("current is missing")
    }

    override suspend fun getMapMetadata(
        productType: String,
        deviceId: String,
        mapId: Long,
        mapVersion: Long
    ): MapMetadataDto = error("not used")

    override suspend fun getMapContent(
        productType: String,
        deviceId: String,
        mapId: Long,
        mapVersion: Long
    ): ResponseBody {
        contentCalls += 1
        return (contentBytes ?: error("content is missing")).toResponseBody()
    }
}
