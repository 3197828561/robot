package com.robot.solar.map

import com.robot.solar.network.http.ApiService
import com.robot.solar.network.http.dto.CurrentMapResponse
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class MapSyncManager(
    private val cacheDir: File,
    private val apiService: ApiService,
    private val parser: PvMapParser = PvMapParser(),
    private val maxMapBytes: Int = MAX_MAP_BYTES
) {
    suspend fun sync(productType: String, deviceId: String, force: Boolean = false): MapSyncResult {
        val current = apiService.getCurrentMap(productType, deviceId)
        val activeMap = current.activeMap
        validateChecksumFormat(activeMap.checksum)
        validateExpectedSize(activeMap.fileSizeBytes)

        val cacheFile = cacheFileFor(productType, deviceId, activeMap.mapId, activeMap.mapVersion)
        if (!force && cacheFile.isFile) {
            val cached = runCatching {
                readValidatedCache(
                    file = cacheFile,
                    expectedMapId = activeMap.mapId,
                    expectedMapVersion = activeMap.mapVersion,
                    expectedChecksum = activeMap.checksum,
                    expectedSizeBytes = activeMap.fileSizeBytes
                )
            }.getOrNull()
            if (cached != null) {
                return MapSyncResult(
                    current = current,
                    pvMap = cached,
                    cacheFile = cacheFile,
                    source = MapSyncSource.CACHE
                )
            }
        }

        val bytes = apiService.getMapContent(
            productType = productType,
            deviceId = deviceId,
            mapId = activeMap.mapId,
            mapVersion = activeMap.mapVersion
        ).use(::readBoundedBytes)
        validateBytes(
            bytes = bytes,
            expectedSizeBytes = activeMap.fileSizeBytes,
            expectedChecksum = activeMap.checksum
        )
        val pvMap = parseAndValidateMap(bytes, activeMap.mapId, activeMap.mapVersion)
        writeCacheAtomically(cacheFile, bytes)
        return MapSyncResult(
            current = current,
            pvMap = pvMap,
            cacheFile = cacheFile,
            source = MapSyncSource.DOWNLOAD
        )
    }

    fun cacheFileFor(productType: String, deviceId: String, mapId: Long, mapVersion: Long): File =
        File(File(File(cacheDir, MAP_CACHE_DIR), productType), deviceId)
            .resolve("${mapId}_${mapVersion}.json")

    private fun readValidatedCache(
        file: File,
        expectedMapId: Long,
        expectedMapVersion: Long,
        expectedChecksum: String,
        expectedSizeBytes: Long
    ): PvMap {
        val length = file.length()
        if (length > maxMapBytes) throw MapSyncException("缓存地图超过大小限制")
        if (length != expectedSizeBytes) throw MapSyncException("缓存地图大小不匹配")
        val bytes = file.readBytes()
        validateBytes(bytes, expectedSizeBytes, expectedChecksum)
        return parseAndValidateMap(bytes, expectedMapId, expectedMapVersion)
    }

    private fun validateBytes(bytes: ByteArray, expectedSizeBytes: Long, expectedChecksum: String) {
        if (bytes.size > maxMapBytes) throw MapSyncException("地图文件超过大小限制")
        if (bytes.size.toLong() != expectedSizeBytes) throw MapSyncException("地图文件大小不匹配")
        val expected = checksumHex(expectedChecksum)
        val actual = sha256(bytes)
        if (!actual.equals(expected, ignoreCase = true)) {
            throw MapSyncException("checksum 不匹配")
        }
    }

    private fun readBoundedBytes(body: ResponseBody): ByteArray {
        val contentLength = body.contentLength()
        if (contentLength > maxMapBytes) throw MapSyncException("地图文件超过大小限制")
        val output = ByteArrayOutputStream(
            if (contentLength in 0..maxMapBytes.toLong()) contentLength.toInt() else DEFAULT_BUFFER_SIZE
        )
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        body.byteStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                total += read
                if (total > maxMapBytes) throw MapSyncException("地图文件超过大小限制")
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    private fun parseAndValidateMap(bytes: ByteArray, expectedMapId: Long, expectedMapVersion: Long): PvMap {
        val map = try {
            parser.parse(bytes.toString(Charsets.UTF_8))
        } catch (error: RuntimeException) {
            throw MapSyncException("地图解析失败", error)
        }
        if (map.mapId != expectedMapId) throw MapSyncException("地图 ID 不匹配")
        if (map.version != expectedMapVersion) throw MapSyncException("地图版本不匹配")
        return map
    }

    private fun writeCacheAtomically(cacheFile: File, bytes: ByteArray) {
        val parent = cacheFile.parentFile ?: throw MapSyncException("地图缓存目录无效")
        if (!parent.exists() && !parent.mkdirs()) throw MapSyncException("地图缓存目录创建失败")
        val temporary = File(parent, "${cacheFile.name}.${System.nanoTime()}.tmp")
        try {
            temporary.writeBytes(bytes)
            try {
                Files.move(
                    temporary.toPath(),
                    cacheFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    cacheFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } catch (error: IOException) {
            throw MapSyncException("地图缓存写入失败", error)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun validateExpectedSize(fileSizeBytes: Long) {
        if (fileSizeBytes < 0) throw MapSyncException("地图文件大小非法")
        if (fileSizeBytes > maxMapBytes) throw MapSyncException("地图文件超过大小限制")
    }

    private fun validateChecksumFormat(checksum: String) {
        checksumHex(checksum)
    }

    private fun checksumHex(checksum: String): String {
        val expected = checksum.removePrefix(CHECKSUM_PREFIX)
        if (!checksum.startsWith(CHECKSUM_PREFIX) || !SHA256_HEX.matches(expected)) {
            throw MapSyncException("checksum 格式非法")
        }
        return expected
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_MAP_BYTES = 20 * 1024 * 1024
        private const val MAP_CACHE_DIR = "maps"
        private const val CHECKSUM_PREFIX = "sha256:"
        private val SHA256_HEX = Regex("^[0-9a-fA-F]{64}$")
    }
}

data class MapSyncResult(
    val current: CurrentMapResponse,
    val pvMap: PvMap,
    val cacheFile: File,
    val source: MapSyncSource
)

enum class MapSyncSource {
    CACHE,
    DOWNLOAD
}

class MapSyncException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
