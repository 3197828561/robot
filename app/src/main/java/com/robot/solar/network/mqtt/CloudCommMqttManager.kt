package com.robot.solar.network.mqtt

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.gson.Gson
import com.robot.solar.BuildConfig
import com.robot.solar.map.PvMap
import com.robot.solar.map.PvMapParser
import com.robot.solar.utils.LogUtils
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject

/** 第二版 App 与 Robot MQTT 通信管理：device/{productType}/{deviceId}/{topicType}。 */
class CloudCommMqttManager private constructor(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val mapParser = PvMapParser(gson)
    private val httpClient = OkHttpClient()
    private var client: MqttClient? = null
    private var boundDeviceId: String? = null
    private var boundProductType: String? = null
    private val connecting = AtomicBoolean(false)
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var mapJob: Job? = null
    private var lastHeartbeatMillis: Long? = null

    private val _mqttConnected = MutableLiveData(false)
    val mqttConnected: LiveData<Boolean> = _mqttConnected

    private val _deviceOnline = MutableLiveData<Boolean?>(null)
    val deviceOnline: LiveData<Boolean?> = _deviceOnline

    private val _batteryPercent = MutableLiveData<Int?>(null)
    val batteryPercent: LiveData<Int?> = _batteryPercent

    private val _status = MutableLiveData<StatusMessage?>(null)
    val status: LiveData<StatusMessage?> = _status

    private val _missionState = MutableLiveData(MissionState())
    val missionState: LiveData<MissionState> = _missionState

    private val _lastCmdFeedback = MutableLiveData<String?>(null)
    val lastCmdFeedback: LiveData<String?> = _lastCmdFeedback

    private val _lastHeartbeatAt = MutableLiveData<Long?>(null)
    val lastHeartbeatAt: LiveData<Long?> = _lastHeartbeatAt

    private val _lastCmdAck = MutableLiveData<CmdAckMessage?>()
    val lastCmdAck: LiveData<CmdAckMessage?> = _lastCmdAck

    private val _mapState = MutableLiveData(MapUiState())
    val mapState: LiveData<MapUiState> = _mapState

    private val _pose = MutableLiveData<PoseMessage?>(null)
    val pose: LiveData<PoseMessage?> = _pose

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch { tryConnectIfNeeded("网络恢复") }
        }

        override fun onLost(network: Network) {
            _mqttConnected.postValue(false)
            markRobotOffline(clearHeartbeat = true)
            LogUtils.system("网络连接已丢失")
        }
    }

    init {
        registerNetworkMonitor()
    }

    private fun registerNetworkMonitor() {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.registerNetworkCallback(request, networkCallback)
        } catch (_: Exception) {
            LogUtils.system("注册网络监听失败")
        }
    }

    /** 绑定设备并连接 Broker、订阅设备上行主题。 */
    fun start(deviceId: String, productType: String = BuildConfig.MQTT_DEFAULT_PRODUCT_TYPE) {
        val changed = boundDeviceId != deviceId || boundProductType != productType
        if (changed) {
            stopClient()
            clearDeviceState()
        }
        boundDeviceId = deviceId
        boundProductType = productType
        startHeartbeatMonitor()
        loadCurrentCachedMapIfNeeded()
        scope.launch { tryConnectIfNeeded("绑定设备 $productType/$deviceId") }
    }

    fun shutdown() {
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        mapJob?.cancel()
        scope.launch {
            stopClient()
            clearDeviceState()
        }
    }

    private fun stopClient() {
        reconnectJob?.cancel()
        try {
            client?.let { old ->
                if (old.isConnected) {
                    try {
                        val productType = boundProductType
                        val deviceId = boundDeviceId
                        if (!productType.isNullOrBlank() && !deviceId.isNullOrBlank()) {
                            old.unsubscribe(
                                arrayOf(
                                    topicHeartbeat(productType, deviceId),
                                    topicStatus(productType, deviceId),
                                    topicCmdAck(productType, deviceId),
                                    topicMap(productType, deviceId),
                                    topicPose(productType, deviceId)
                                )
                            )
                        }
                    } catch (_: Exception) {
                    }
                    old.disconnect()
                }
                old.close()
            }
        } catch (_: Exception) {
        } finally {
            client = null
            _mqttConnected.postValue(false)
            markRobotOffline(clearHeartbeat = true)
        }
    }

    /**
     * 清除只能由当前 Robot 会话证明的运行态。
     *
     * 断线后不得保留 missionId 或手动模式确认，否则心跳早于 status 恢复时，
     * App 可能把旧任务 ID 用于新会话。地图缓存不属于会话运行态，继续保留。
     */
    private fun markRobotOffline(clearHeartbeat: Boolean) {
        if (clearHeartbeat) lastHeartbeatMillis = null
        _deviceOnline.postValue(false)
        _batteryPercent.postValue(null)
        _status.postValue(null)
        _missionState.postValue(MissionState())
        _pose.postValue(null)
    }

    private fun clearDeviceState() {
        lastHeartbeatMillis = null
        _lastHeartbeatAt.postValue(null)
        _deviceOnline.postValue(null)
        _batteryPercent.postValue(null)
        _status.postValue(null)
        _missionState.postValue(MissionState())
        _lastCmdAck.postValue(null)
        _lastCmdFeedback.postValue(null)
        _mapState.postValue(MapUiState())
        _pose.postValue(null)
    }

    private fun loadCurrentCachedMapIfNeeded(force: Boolean = false) {
        if (!force && _mapState.value?.status != MapLoadStatus.NO_MAP) return
        scope.launch {
            val cached = runCatching {
                val map = readCurrentMapRecord() ?: return@runCatching null
                val mapId = map.mapId ?: return@runCatching null
                val version = map.mapVersion ?: return@runCatching null
                val file = cacheFileFor(mapId, version)
                if (!file.exists()) return@runCatching null
                verifyChecksumIfNeeded(file.readBytes(), map.checksum)
                val pvMap = mapParser.parse(file)
                require(pvMap.mapId == mapId) { "地图 ID 与记录不一致" }
                require(pvMap.version == version) { "地图版本与记录不一致" }
                map to file to pvMap
            }.getOrNull()

            if (cached != null) {
                val (mapAndFile, pvMap) = cached
                val (map, file) = mapAndFile
                _mapState.postValue(
                    MapUiState(
                        status = MapLoadStatus.READY,
                        message = "已加载本地缓存地图",
                        map = map,
                        cachePath = file.absolutePath,
                        pvMap = pvMap
                    )
                )
            }
        }
    }

    private suspend fun tryConnectIfNeeded(reason: String) {
        val deviceId = boundDeviceId ?: return
        if (connecting.get()) return
        if (client?.isConnected == true) return
        if (!connecting.compareAndSet(false, true)) return
        try {
            withContext(Dispatchers.IO) { connectInternal(deviceId, reason) }
        } finally {
            connecting.set(false)
        }
    }

    private fun connectInternal(deviceId: String, reason: String) {
        val productType = boundProductType ?: BuildConfig.MQTT_DEFAULT_PRODUCT_TYPE
        try {
            LogUtils.system("MQTT 连接：$reason")
            client?.let { old ->
                try {
                    if (old.isConnected) old.disconnect()
                } catch (_: Exception) {
                }
                try {
                    old.close()
                } catch (_: Exception) {
                }
            }
            client = null

            val serverUri = "tcp://${BuildConfig.MQTT_HOST}:${BuildConfig.MQTT_PORT}"
            val clientId = "solar_app_${UUID.randomUUID().toString().take(8)}"
            val mqttClient = MqttClient(serverUri, clientId, MemoryPersistence())
            val options = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 15
                keepAliveInterval = 20
                isAutomaticReconnect = false
                // 当前服务器与硬件联调按 MQTT 3.1.1 验证通过，显式固定协议版本。
                mqttVersion = MqttConnectOptions.MQTT_VERSION_3_1_1
                if (BuildConfig.MQTT_USERNAME.isNotBlank()) {
                    userName = BuildConfig.MQTT_USERNAME
                    password = BuildConfig.MQTT_PASSWORD.toCharArray()
                }
            }
            mqttClient.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    _mqttConnected.postValue(true)
                    LogUtils.system("MQTT 已连接")
                }

                override fun connectionLost(cause: Throwable?) {
                    _mqttConnected.postValue(false)
                    markRobotOffline(clearHeartbeat = true)
                    LogUtils.system("设备连接已断开，正在尝试恢复")
                    scheduleReconnect()
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    handleMessage(topic.orEmpty(), message?.toString().orEmpty())
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
            })
            mqttClient.connect(options)
            client = mqttClient
            _mqttConnected.postValue(true)
            subscribeTopics(mqttClient, productType, deviceId)
        } catch (e: Exception) {
            _mqttConnected.postValue(false)
            LogUtils.system("设备连接失败，请检查网络")
            scheduleReconnect()
        }
    }

    private fun subscribeTopics(mqttClient: MqttClient, productType: String, deviceId: String) {
        val topics = arrayOf(
            topicHeartbeat(productType, deviceId),
            topicStatus(productType, deviceId),
            topicCmdAck(productType, deviceId),
            topicMap(productType, deviceId),
            topicPose(productType, deviceId)
        )
        mqttClient.subscribe(topics, IntArray(topics.size) { COMMAND_QOS })
        LogUtils.system("已订阅 device/$productType/$deviceId/* 上行主题")
    }

    private fun handleMessage(topic: String, payload: String) {
        try {
            val obj = JSONObject(payload)
            if (!isValidEnvelope(obj, topic)) return

            when {
                topic.endsWith("/heartbeat") -> {
                    val msg = gson.fromJson(payload, HeartbeatMessage::class.java)
                    if (msg.online == true) {
                        val now = System.currentTimeMillis()
                        lastHeartbeatMillis = now
                        _lastHeartbeatAt.postValue(now)
                        _deviceOnline.postValue(true)
                    } else {
                        markRobotOffline(clearHeartbeat = true)
                    }
                    LogUtils.device(if (msg.online == true) "收到设备在线心跳" else "收到设备离线通知")
                }
                topic.endsWith("/status") -> {
                    val msg = gson.fromJson(payload, StatusMessage::class.java)
                    _status.postValue(msg)
                    _missionState.postValue(
                        MissionState(
                            missionId = msg.missionId,
                            taskKind = msg.taskKind,
                            runState = msg.runState,
                            operationalMode = msg.operationalMode,
                            safetyState = msg.safetyState,
                            phase = msg.phase,
                            activeAction = msg.activeAction,
                            waypointIndex = msg.waypointIndex,
                            waypointCount = msg.waypointCount,
                            errorCode = msg.missionErrorCode,
                            errorRetryable = msg.errorRetryable,
                            errorSource = msg.errorSource,
                            errorMessage = msg.errorMessage
                        )
                    )
                    msg.batteryPercent?.let { _batteryPercent.postValue(it.toInt().coerceIn(0, 100)) }
                    LogUtils.device("设备运行状态已更新")
                }
                topic.endsWith("/cmd_ack") -> {
                    val ack = gson.fromJson(payload, CmdAckMessage::class.java)
                    _lastCmdAck.postValue(ack)
                    val text = when (ack.ackStatus) {
                        "success" -> "设备已确认执行结果"
                        "failed" -> "设备未能执行操作"
                        else -> "收到设备操作反馈"
                    }
                    _lastCmdFeedback.postValue(text)
                    LogUtils.device("cmd_ack：$text")
                }
                topic.endsWith("/map") -> {
                    val map = gson.fromJson(payload, MapMessage::class.java)
                    handleMapMessage(map)
                    LogUtils.device("收到地图更新通知，地图编号：${map.mapId ?: "--"}")
                }
                topic.endsWith("/pose") -> {
                    val pose = gson.fromJson(payload, PoseMessage::class.java)
                    _pose.postValue(pose)
                    LogUtils.device("机器人地图位置已更新")
                }
            }
        } catch (e: Exception) {
            LogUtils.system("收到无法识别的设备消息")
        }
    }

    private fun isValidEnvelope(obj: JSONObject, topic: String): Boolean {
        val version = obj.optString("version")
        if (version != PROTOCOL_VERSION) {
            LogUtils.system("忽略 MQTT 消息：version 不匹配 topic=$topic version=$version")
            return false
        }

        val productType = obj.optString("productType")
        val expectedProductType = boundProductType
        if (!expectedProductType.isNullOrBlank() && productType != expectedProductType) {
            LogUtils.system("忽略 MQTT 消息：productType 不匹配 expected=$expectedProductType actual=$productType")
            return false
        }

        val deviceId = obj.optString("deviceId")
        val expectedDeviceId = boundDeviceId
        if (!expectedDeviceId.isNullOrBlank() && deviceId != expectedDeviceId) {
            LogUtils.system("忽略 MQTT 消息：deviceId 不匹配 expected=$expectedDeviceId actual=$deviceId")
            return false
        }

        return true
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(3000)
            tryConnectIfNeeded("自动重连")
        }
    }

    private fun startHeartbeatMonitor() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            while (true) {
                val last = lastHeartbeatMillis
                val online = last != null && System.currentTimeMillis() - last <= HEARTBEAT_TIMEOUT_MS
                if (online) {
                    _deviceOnline.postValue(true)
                } else if (last != null) {
                    markRobotOffline(clearHeartbeat = true)
                } else {
                    _deviceOnline.postValue(false)
                }
                delay(500)
            }
        }
    }

    /**
     * 创建可重放的命令。重试时必须复用同一个 PreparedCommand，确保 cmdId 和 payload 不变。
     */
    fun prepareCommand(
        action: String,
        params: Any = emptyMap<String, Any?>(),
        cmdId: String = newCmdId()
    ): PreparedCommand? {
        if (action !in SUPPORTED_CMDS) {
            LogUtils.system("拒绝发送未知命令：$action")
            return null
        }
        val deviceId = boundDeviceId ?: return null
        val productType = boundProductType ?: BuildConfig.MQTT_DEFAULT_PRODUCT_TYPE
        return CommandPayloadFactory.create(
            version = PROTOCOL_VERSION,
            cmdId = cmdId,
            deviceId = deviceId,
            productType = productType,
            timestamp = nowTimestamp(),
            cmd = action,
            params = params,
            gson = gson
        )
    }

    /** 发布已准备好的任务命令；ACK 仅表示 Robot 任务层已受理。 */
    fun publishCmd(command: PreparedCommand): CommandPublishResult {
        val deviceId = boundDeviceId ?: return CommandPublishResult(false, null, command.cmd)
        val productType = boundProductType ?: BuildConfig.MQTT_DEFAULT_PRODUCT_TYPE
        val ok = publish(topicCmd(productType, deviceId), command.payload, COMMAND_QOS)
        return CommandPublishResult(ok, if (ok) command.cmdId else null, command.cmd)
    }

    /** 遥控速度：线速度单位 cm/s，前进为正；角速度单位 rad/s。 */
    fun publishRemote(linearSpeedCms: Double, angularRadps: Double, durationMs: Int = 300): Boolean {
        val deviceId = boundDeviceId ?: return false
        val productType = boundProductType ?: BuildConfig.MQTT_DEFAULT_PRODUCT_TYPE
        val safeLinear = RemoteControlContract.clampLinear(linearSpeedCms)
        val safeAngular = RemoteControlContract.clampAngular(angularRadps)
        val json = JSONObject()
            .put("version", PROTOCOL_VERSION)
            .put("deviceId", deviceId)
            .put("productType", productType)
            .put("timestamp", nowTimestamp())
            .put("linearSpeedCms", safeLinear)
            .put("angularSpeedRadps", safeAngular)
            .put("durationMs", durationMs)
        return publish(topicRemote(productType, deviceId), json.toString(), REMOTE_QOS)
    }

    fun retryMapDownload() {
        val map = _mapState.value?.map ?: run {
            loadCurrentCachedMapIfNeeded(force = true)
            return
        }
        if (map.mapJsonUrl.isNullOrBlank()) {
            loadCurrentCachedMapIfNeeded(force = true)
            return
        }
        handleMapMessage(map, forceDownload = true)
    }

    private fun handleMapMessage(map: MapMessage, forceDownload: Boolean = false) {
        val url = map.mapJsonUrl?.takeIf { it.isNotBlank() }
        val mapId = map.mapId
        val version = map.mapVersion
        if (url == null || mapId == null || version == null) {
            val current = _mapState.value
            if (current?.pvMap == null) {
                loadCurrentCachedMapIfNeeded(force = true)
            } else {
                _mapState.postValue(current.copy(message = "未收到有效地图更新，继续显示当前地图"))
            }
            return
        }
        mapJob?.cancel()
        mapJob = scope.launch {
            val previous = _mapState.value
            _mapState.postValue(
                previous?.copy(status = MapLoadStatus.DOWNLOADING, message = "正在加载", map = map)
                    ?: MapUiState(status = MapLoadStatus.DOWNLOADING, message = "正在加载", map = map)
            )
            val result = runCatching { downloadAndCacheMap(map, url, mapId, version, forceDownload) }
            _mapState.postValue(
                result.fold(
                    onSuccess = { (path, pvMap) ->
                        saveCurrentMapRecord(map)
                        MapUiState(status = MapLoadStatus.READY, message = "地图已加载", map = map, cachePath = path, pvMap = pvMap)
                    },
                    onFailure = { error ->
                        LogUtils.system("地图加载失败，请重新加载")
                        previous?.copy(
                            status = if (previous.pvMap == null) MapLoadStatus.FAILED else previous.status,
                            message = error.message ?: "地图加载失败，继续显示当前地图",
                            map = previous.map ?: map
                        ) ?: MapUiState(status = MapLoadStatus.FAILED, message = error.message ?: "地图加载失败", map = map)
                    }
                )
            )
        }
    }

    private fun downloadAndCacheMap(
        map: MapMessage,
        url: String,
        mapId: Long,
        version: Long,
        forceDownload: Boolean
    ): Pair<String, PvMap> {
        require(url.startsWith("https://") || url.startsWith("http://")) { "mapJsonUrl 不是 HTTP/HTTPS" }
        val cacheFile = cacheFileFor(mapId, version)
        if (forceDownload) cacheFile.delete()
        if (!cacheFile.exists()) {
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                require(response.isSuccessful) { "HTTP ${response.code}" }
                val body = response.body?.bytes() ?: error("地图响应为空")
                require(body.size <= MAX_MAP_BYTES) { "地图文件过大" }
                map.fileSizeBytes?.let { require(it == body.size.toLong()) { "地图文件大小不匹配" } }
                verifyChecksumIfNeeded(body, map.checksum)
                val parent = cacheFile.parentFile ?: error("地图缓存目录无效")
                parent.mkdirs()
                val temporary = File(parent, "${mapId}_${version}.tmp")
                temporary.writeBytes(body)
                require(temporary.renameTo(cacheFile)) { "地图缓存写入失败" }
            }
        } else {
            try {
                verifyChecksumIfNeeded(cacheFile.readBytes(), map.checksum)
            } catch (error: Exception) {
                cacheFile.delete()
                throw error
            }
        }
        val pvMap = mapParser.parse(cacheFile)
        require(pvMap.mapId == mapId) { "地图 ID 与通知不一致" }
        require(pvMap.version == version) { "地图版本与通知不一致" }
        return cacheFile.absolutePath to pvMap
    }

    private fun cacheFileFor(mapId: Long, version: Long): File {
        val productType = boundProductType ?: BuildConfig.MQTT_DEFAULT_PRODUCT_TYPE
        val deviceId = boundDeviceId ?: BuildConfig.MQTT_DEFAULT_DEVICE_ID
        return File(File(File(appContext.cacheDir, MAP_CACHE_DIR), productType), deviceId)
            .resolve("${mapId}_${version}.json")
    }

    private fun currentMapRecordKey(): String {
        val productType = boundProductType ?: BuildConfig.MQTT_DEFAULT_PRODUCT_TYPE
        val deviceId = boundDeviceId ?: BuildConfig.MQTT_DEFAULT_DEVICE_ID
        return "${productType}_${deviceId}"
    }

    private fun readCurrentMapRecord(): MapMessage? {
        val prefs = appContext.getSharedPreferences(MAP_PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(currentMapRecordKey(), null) ?: return null
        return runCatching { gson.fromJson(raw, MapMessage::class.java) }.getOrNull()
    }

    private fun saveCurrentMapRecord(map: MapMessage) {
        val json = gson.toJson(map.copy(timestamp = null))
        appContext.getSharedPreferences(MAP_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(currentMapRecordKey(), json)
            .apply()
    }

    private fun verifyChecksumIfNeeded(bytes: ByteArray, checksum: String?) {
        val expected = checksum?.removePrefix("sha256:")?.takeIf { it.isNotBlank() } ?: return
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        require(actual.equals(expected, ignoreCase = true)) { "checksum 不匹配" }
    }

    private fun publish(topic: String, json: String, qos: Int): Boolean {
        val mqttClient = client
        if (mqttClient == null || !mqttClient.isConnected) {
            scope.launch { tryConnectIfNeeded("发送前补连") }
            return false
        }
        return try {
            val message = MqttMessage(json.toByteArray(Charsets.UTF_8))
            message.qos = qos
            message.isRetained = false
            mqttClient.publish(topic, message)
            true
        } catch (e: Exception) {
            LogUtils.system("操作发送失败，请检查设备连接")
            false
        }
    }

    private fun newCmdId(): String {
        val device = boundDeviceId.orEmpty().ifBlank { "device" }
        return "cmd_${device}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
    }

    private fun nowTimestamp(): String =
        COMMAND_TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(System.currentTimeMillis()))

    companion object {
        private const val PROTOCOL_VERSION = "1.0"
        private const val COMMAND_QOS = 1
        private const val REMOTE_QOS = 0
        private const val HEARTBEAT_TIMEOUT_MS = 3000L
        private const val MAX_MAP_BYTES = 20 * 1024 * 1024
        private const val MAP_CACHE_DIR = "maps"
        private const val MAP_PREFS_NAME = "map_cache"
        private val COMMAND_TIMESTAMP_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .withZone(ZoneOffset.UTC)
        private val SUPPORTED_CMDS = setOf(
            "start",
            "stop",
            "pause",
            "resume",
            "replan",
            "manual",
            "auto",
            "estop",
            "clear_estop"
        )

        fun topicHeartbeat(productType: String, deviceId: String) = "device/$productType/$deviceId/heartbeat"
        fun topicStatus(productType: String, deviceId: String) = "device/$productType/$deviceId/status"
        fun topicCmdAck(productType: String, deviceId: String) = "device/$productType/$deviceId/cmd_ack"
        fun topicMap(productType: String, deviceId: String) = "device/$productType/$deviceId/map"
        fun topicPose(productType: String, deviceId: String) = "device/$productType/$deviceId/pose"
        fun topicCmd(productType: String, deviceId: String) = "device/$productType/$deviceId/cmd"
        fun topicRemote(productType: String, deviceId: String) = "device/$productType/$deviceId/remote"

        @Volatile
        private var INSTANCE: CloudCommMqttManager? = null

        fun getInstance(context: Context): CloudCommMqttManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CloudCommMqttManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
