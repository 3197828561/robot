package com.robot.solar

import com.robot.solar.map.MapSyncException
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class MapSyncManagerTest {
    @Test
    fun sync_usesValidCacheWithoutDownloadingContent() = runBlocking {
        val cacheDir = temporaryDirectory()
        val bytes = validMapBytes(mapId = 2, version = 1)
        val api = FakeApiService(current = currentFor(bytes, mapId = 2, version = 1), contentBytes = null)
        val manager = MapSyncManager(cacheDir, api)
        val cacheFile = manager.cacheFileFor("crawler", "crawler_1", 2, 1)
        cacheFile.parentFile!!.mkdirs()
        cacheFile.writeBytes(bytes)

        val result = manager.sync("crawler", "crawler_1")

        assertEquals(MapSyncSource.CACHE, result.source)
        assertEquals(2L, result.pvMap.mapId)
        assertEquals(1L, result.pvMap.version)
        assertEquals(0, api.contentCalls)
    }

    @Test
    fun sync_downloadsContentWhenCacheChecksumIsWrong() = runBlocking {
        val cacheDir = temporaryDirectory()
        val bytes = validMapBytes(mapId = 2, version = 1)
        val api = FakeApiService(current = currentFor(bytes, mapId = 2, version = 1), contentBytes = bytes)
        val manager = MapSyncManager(cacheDir, api)
        val cacheFile = manager.cacheFileFor("crawler", "crawler_1", 2, 1)
        cacheFile.parentFile!!.mkdirs()
        cacheFile.writeBytes(validMapBytes(mapId = 3, version = 1))

        val result = manager.sync("crawler", "crawler_1")

        assertEquals(MapSyncSource.DOWNLOAD, result.source)
        assertEquals(1, api.contentCalls)
        assertEquals(sha256(bytes), sha256(cacheFile.readBytes()))
    }

    @Test
    fun sync_failsChecksumMismatchWithoutOverwritingExistingCache() = runBlocking {
        val cacheDir = temporaryDirectory()
        val oldBytes = validMapBytes(mapId = 2, version = 1)
        val downloadedBytes = validMapBytes(mapId = 2, version = 1).replaceFirstByte()
        val api = FakeApiService(current = currentFor(oldBytes, mapId = 2, version = 1), contentBytes = downloadedBytes)
        val manager = MapSyncManager(cacheDir, api)
        val cacheFile = manager.cacheFileFor("crawler", "crawler_1", 2, 1)
        cacheFile.parentFile!!.mkdirs()
        cacheFile.writeBytes(oldBytes)

        val failed = runCatching { manager.sync("crawler", "crawler_1", force = true) }

        assertTrue(failed.exceptionOrNull() is MapSyncException)
        assertEquals(1, api.contentCalls)
        assertEquals(sha256(oldBytes), sha256(cacheFile.readBytes()))
    }

    @Test
    fun sync_failsWhenParsedMapIdOrVersionDoesNotMatchCurrent() = runBlocking {
        val cacheDir = temporaryDirectory()
        val mismatchedBytes = validMapBytes(mapId = 3, version = 1)
        val api = FakeApiService(current = currentFor(mismatchedBytes, mapId = 2, version = 1), contentBytes = mismatchedBytes)
        val manager = MapSyncManager(cacheDir, api)
        val cacheFile = manager.cacheFileFor("crawler", "crawler_1", 2, 1)

        val failed = runCatching { manager.sync("crawler", "crawler_1") }

        assertTrue(failed.exceptionOrNull() is MapSyncException)
        assertFalse(cacheFile.exists())
    }

    @Test
    fun cacheFileFor_separatesDifferentDeviceIds() {
        val cacheDir = temporaryDirectory()
        val manager = MapSyncManager(cacheDir, FakeApiService(current = currentFor(validMapBytes(), 2, 1), contentBytes = validMapBytes()))

        val first = manager.cacheFileFor("crawler", "crawler_1", 2, 1)
        val second = manager.cacheFileFor("crawler", "crawler_2", 2, 1)

        assertEquals(File(File(File(cacheDir, "maps"), "crawler"), "crawler_1").resolve("2_1.json"), first)
        assertEquals(File(File(File(cacheDir, "maps"), "crawler"), "crawler_2").resolve("2_1.json"), second)
        assertTrue(first.absolutePath != second.absolutePath)
    }

    private fun temporaryDirectory(): File =
        Files.createTempDirectory("map-sync-test").toFile().apply { deleteOnExit() }

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

    private fun validMapBytes(mapId: Long = 2, version: Long = 1): ByteArray =
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

    private fun ByteArray.replaceFirstByte(): ByteArray =
        copyOf().also { it[0] = if (it[0] == '{'.code.toByte()) '['.code.toByte() else '{'.code.toByte() }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}

private class FakeApiService(
    private val current: CurrentMapResponse,
    private val contentBytes: ByteArray?
) : ApiService {
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
    override suspend fun getCurrentMap(productType: String, deviceId: String): CurrentMapResponse = current
    override suspend fun getMapMetadata(productType: String, deviceId: String, mapId: Long, mapVersion: Long): MapMetadataDto = error("not used")
    override suspend fun getMapContent(productType: String, deviceId: String, mapId: Long, mapVersion: Long): ResponseBody {
        contentCalls += 1
        return (contentBytes ?: error("不应下载 content")).toResponseBody()
    }
}
