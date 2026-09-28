package com.robot.solar

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
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
import com.robot.solar.network.mqtt.CmdAckMessage
import com.robot.solar.network.mqtt.CommandPublishResult
import com.robot.solar.network.mqtt.DeviceTopicIdentity
import com.robot.solar.network.mqtt.MissionState
import com.robot.solar.network.mqtt.PoseMessage
import com.robot.solar.network.mqtt.PreparedCommand
import com.robot.solar.network.mqtt.StatusMessage
import com.robot.solar.viewmodel.MainDeviceIdentityProvider
import com.robot.solar.viewmodel.MainMapRepository
import com.robot.solar.viewmodel.MainMqttGateway
import com.robot.solar.viewmodel.MainViewModelMapStartup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File

class MainViewModelMapRepositoryTest {
    @Test
    fun onScreenReady_startsMqttAndTriggersMapSync() {
        val mqtt = FakeMainMqttGateway()
        val mapRepository = FakeMainMapRepository(result = mapSyncResult())

        runScreenReady(mqtt = mqtt, mapRepository = mapRepository)

        assertEquals("crawler_1", mqtt.startedDeviceId)
        assertEquals("crawler", mqtt.startedProductType)
        assertEquals("crawler", mapRepository.syncedProductType)
        assertEquals("crawler_1", mapRepository.syncedDeviceId)
        assertEquals(false, mapRepository.syncedForce)
    }

    @Test
    fun onScreenReady_exposesSuccessfulHttpMapV2State() {
        val result = mapSyncResult(mapId = 5, version = 3)
        val mapRepository = FakeMainMapRepository(result = result)

        runScreenReady(mapRepository = mapRepository)

        assertSame(result, mapRepository.state.value.currentResult)
        assertEquals(5L, mapRepository.state.value.currentResult!!.pvMap.mapId)
        assertEquals(3L, mapRepository.state.value.currentResult!!.pvMap.version)
    }

    @Test
    fun onScreenReady_mapSyncFailureDoesNotPreventMqttStart() {
        val failure = IllegalStateException("sync failed")
        val mqtt = FakeMainMqttGateway()
        val mapRepository = FakeMainMapRepository(failure = failure)

        runScreenReady(mqtt = mqtt, mapRepository = mapRepository)

        assertEquals("crawler_1", mqtt.startedDeviceId)
        assertEquals("crawler", mqtt.startedProductType)
        assertSame(failure, mapRepository.state.value.error)
    }

    private fun runScreenReady(
        mqtt: FakeMainMqttGateway = FakeMainMqttGateway(),
        mapRepository: FakeMainMapRepository
    ) {
        MainViewModelMapStartup.onScreenReady(
            mqtt = mqtt,
            deviceIdentityProvider = FakeDeviceIdentityProvider(),
            mapRepository = mapRepository,
            mapSyncLauncher = { block -> runBlocking { block() } }
        )
    }

    private fun mapSyncResult(mapId: Long = 2, version: Long = 1): MapSyncResult {
        val current = CurrentMapResponse(
            productType = "crawler",
            deviceId = "crawler_1",
            activeRevision = 1,
            activeMap = ActiveMapDto(
                mapId = mapId,
                mapVersion = version,
                mapName = "test-map",
                checksum = "sha256:${"0".repeat(64)}",
                fileSizeBytes = 2,
                contentUrl = "https://example.test/map.json"
            ),
            activatedAt = "2026-08-19T00:00:00.000Z",
            lastReportedAt = "2026-08-19T00:00:00.000Z"
        )
        return MapSyncResult(
            current = current,
            pvMap = PvMap(
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
            ),
            cacheFile = File("test-cache.json"),
            source = MapSyncSource.DOWNLOAD
        )
    }
}

private class FakeMainMqttGateway : MainMqttGateway {
    override val mqttConnected: LiveData<Boolean> = MutableLiveData(false)
    override val deviceOnline: LiveData<Boolean?> = MutableLiveData(false)
    override val batteryPercent: LiveData<Int?> = MutableLiveData(null)
    override val status: LiveData<StatusMessage?> = MutableLiveData(null)
    override val missionState: LiveData<MissionState> = MutableLiveData(MissionState())
    override val lastHeartbeatAt: LiveData<Long?> = MutableLiveData(null)
    override val pose: LiveData<PoseMessage?> = MutableLiveData(null)
    override val lastCmdAck: LiveData<CmdAckMessage?> = MutableLiveData(null)
    var startedDeviceId: String? = null
        private set
    var startedProductType: String? = null
        private set

    override fun start(deviceId: String, productType: String) {
        startedDeviceId = deviceId
        startedProductType = productType
    }

    override fun publishRemote(linearSpeedCms: Double, angularRadps: Double): Boolean = true
    override fun prepareCommand(action: String, params: Any): PreparedCommand? = null
    override fun publishCmd(command: PreparedCommand): CommandPublishResult = CommandPublishResult(false, null, command.cmd)
    override fun shutdown() = Unit
}

private class FakeDeviceIdentityProvider : MainDeviceIdentityProvider {
    override fun currentDeviceName(): String? = "履带机器人测试"
    override fun currentDeviceId(): String? = "crawler_1"
    override fun currentProductType(): String? = "crawler"
    override fun currentMqttIdentity(): DeviceTopicIdentity = DeviceTopicIdentity("crawler", "crawler_1")
}

private class FakeMainMapRepository(
    private val result: MapSyncResult? = null,
    private val failure: Throwable? = null
) : MainMapRepository {
    private val _state = MutableStateFlow(MapRepositoryState())
    override val state: StateFlow<MapRepositoryState> = _state
    var syncedProductType: String? = null
        private set
    var syncedDeviceId: String? = null
        private set
    var syncedForce: Boolean? = null
        private set

    override suspend fun syncCurrentMap(productType: String, deviceId: String, force: Boolean): MapSyncResult {
        syncedProductType = productType
        syncedDeviceId = deviceId
        syncedForce = force
        failure?.let {
            _state.value = MapRepositoryState(isSyncing = false, error = it)
            throw it
        }
        val value = result ?: error("result is missing")
        _state.value = MapRepositoryState(isSyncing = false, currentResult = value)
        return value
    }
}
