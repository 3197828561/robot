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
import com.robot.solar.utils.LogUtils
import java.io.File
import java.security.MessageDigest
import java.time.Instant
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

/**
 * 第一版 APP 与 Robot MQTT 管理：device/{productType}/{deviceId}/{topicType}。
 */
class CloudCommMqttManager private constructor(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
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

    private val _lastCmdFeedback = MutableLiveData<String?>(null)
    val lastCmdFeedback: LiveData<String?> = _lastCmdFeedback

    private val _lastHeartbeatAt = MutableLiveData<Long?>(null)
    val lastHeartbeatAt: LiveData<Long?> = _lastHeartbeatAt

    private val _lastCmdAck = MutableLiveData<CmdAckMessage?>()
    val lastCmdAck: LiveData<CmdAckMessage?> = _lastCmdAck

    private val _mapState = MutableLiveData(MapUiState())
    val mapState: LiveData<MapUiState> = _mapState

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch { tryConnectIfNeeded("网络恢复") }
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

    /** 绑定设备并连接 Broker、订阅上行主题 */
    fun start(deviceId: String, productType: String = BuildConfig.MQTT_DEFAULT_PRODUCT_TYPE) {
        val changed = boundDeviceId != deviceId || boundProductType != productType
        if (changed) {
            stopClient()
            clearDeviceState()
        }
        boundDeviceId = deviceId
        boundProductType = productType
        startHeartbeatMonitor()
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
                                    topicMap(productType, deviceId)
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
            _deviceOnline.postValue(false)
        }
    }

    private fun clearDeviceState() {
        lastHeartbeatMillis = null
        _lastHeartbeatAt.postValue(null)
        _deviceOnline.postValue(null)
        _batteryPercent.postValue(null)
        _status.postValue(null)
        _lastCmdAck.postValue(null)
        _lastCmdFeedback.postValue(null)
        _mapState.postValue(MapUiState())
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
                // 当前服务器与硬件组联调已按 MQTT 3.1.1 验证通过，显式固定协议版本。
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
                    _deviceOnline.postValue(false)
                    LogUtils.system("MQTT 断开：${cause?.message ?: "未知"}")
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
            LogUtils.system("MQTT 连接失败：${e.message}")
            scheduleReconnect()
        }
    }

    private fun subscribeTopics(mqttClient: MqttClient, productType: String, deviceId: String) {
        val topics = arrayOf(
            topicHeartbeat(productType, deviceId),
            topicStatus(productType, deviceId),
            topicCmdAck(productType, deviceId),
            topicMap(productType, deviceId)
        )
        val qos = IntArray(topics.size) { QOS }
        mqttClient.subscribe(topics, qos)
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
                    }
                    LogUtils.device("heartbeat：online=${msg.online}")
                }
                topic.endsWith("/status") -> {
                    val msg = gson.fromJson(payload, StatusMessage::class.java)
                    _status.postValue(msg)
                    msg.batteryPercent?.let {
                            _batteryPercent.postValue(it.toInt().coerceIn(0, 100))
                        }
                    LogUtils.device("status：battery=${msg.batteryPercent}, work=${msg.workStatus}")
                }
                topic.endsWith("/cmd_ack") -> {
                    val ack = gson.fromJson(payload, CmdAckMessage::class.java)
                    _lastCmdAck.postValue(ack)
                    val text = when (ack.ackStatus) {
                        "success" -> "指令执行成功：${ack.cmd ?: ""}"
                        "failed" -> "指令执行失败：${ack.message ?: ack.errorCode ?: ""}"
                        else -> "指令回执：${ack.ackStatus ?: "未知"}"
                    }
                    _lastCmdFeedback.postValue(text)
                    LogUtils.device("cmd_ack：$text")
                }
                topic.endsWith("/map") -> {
                    val map = gson.fromJson(payload, MapMessage::class.java)
                    handleMapMessage(map)
                    LogUtils.device("map：${map.mapId ?: ""} ${map.mapJsonUrl ?: ""}")
                }
            }
        } catch (e: Exception) {
            LogUtils.system("MQTT 消息解析失败：${e.message}")
        }
    }

    /**
     * 所有硬件上行消息必须先通过设备与产品类型校验。
     * 这样可避免公共/联调 Broker 上其他设备或旧协议消息影响当前设备 UI。
     */
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
                _deviceOnline.postValue(online)
                delay(500)
            }
        }
    }

    /** 低频系统命令：start / stop / estop / clear_estop */
    fun publishCmd(action: String): CommandPublishResult {
        if (action !in SUPPORTED_CMDS) {
            LogUtils.system("拒绝发送未知命令：$action")
            return CommandPublishResult(false, null, action)
        }
        val deviceId = boundDeviceId ?: return CommandPublishResult(false, null, action)
        val productType = boundProductType ?: BuildConfig.MQTT_DEFAULT_PRODUCT_TYPE
        val cmdId = newCmdId()
        val json = JSONObject()
            .put("version", PROTOCOL_VERSION)
            .put("cmdId", cmdId)
            .put("deviceId", deviceId)
            .put("productType", productType)
            .put("timestamp", nowTimestamp())
            .put("cmd", action)
            .put("params", JSONObject())
        val ok = publish(topicCmd(productType, deviceId), json.toString())
        return CommandPublishResult(ok, if (ok) cmdId else null, action)
    }

    /** 遥控速度：线速度单位 cm/s，前进为正；角速度单位 rad/s。 */
    fun publishRemote(linearSpeedCms: Double, angularRadps: Double, durationMs: Int = 300): Boolean {
        val deviceId = boundDeviceId ?: return false
        val productType = boundProductType ?: BuildConfig.MQTT_DEFAULT_PRODUCT_TYPE
        val safeLinear = linearSpeedCms.coerceIn(-20.0, 20.0)
        val safeAngular = angularRadps.coerceIn(-0.5, 0.5)
        val json = JSONObject()
            .put("version", PROTOCOL_VERSION)
            .put("deviceId", deviceId)
            .put("productType", productType)
            .put("timestamp", nowTimestamp())
            .put("linearSpeedCms", safeLinear)
            .put("angularSpeedRadps", safeAngular)
            .put("durationMs", durationMs)
        return publish(topicRemote(productType, deviceId), json.toString())
    }

    private fun handleMapMessage(map: MapMessage) {
        val url = map.mapJsonUrl?.takeIf { it.isNotBlank() }
        val mapId = map.mapId?.takeIf { it.isNotBlank() }
        val version = map.mapVersion
        if (url == null || mapId == null || version == null) {
            _mapState.postValue(MapUiState(status = MapLoadStatus.NO_MAP, message = "暂无地图", map = map))
            return
        }
        mapJob?.cancel()
        mapJob = scope.launch {
            _mapState.postValue(MapUiState(status = MapLoadStatus.DOWNLOADING, message = "正在加载", map = map))
            val result = runCatching { downloadAndCacheMap(map, url, mapId, version) }
            _mapState.postValue(
                result.fold(
                    onSuccess = { path ->
                        MapUiState(status = MapLoadStatus.READY, message = "地图已加载", map = map, cachePath = path)
                    },
                    onFailure = { error ->
                        LogUtils.system("地图加载失败：${error.message}")
                        MapUiState(status = MapLoadStatus.FAILED, message = "地图加载失败", map = map)
                    }
                )
            )
        }
    }

    private fun downloadAndCacheMap(map: MapMessage, url: String, mapId: String, version: Int): String {
        require(url.startsWith("https://") || url.startsWith("http://")) { "mapJsonUrl 不是 HTTP/HTTPS" }
        val cacheDir = File(appContext.cacheDir, "maps").apply { mkdirs() }
        val cacheFile = File(cacheDir, "${mapId}_${version}.json")
        if (!cacheFile.exists()) {
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                require(response.isSuccessful) { "HTTP ${response.code}" }
                val body = response.body?.bytes() ?: error("地图响应为空")
                verifyChecksumIfNeeded(body, map.checksum)
                cacheFile.writeBytes(body)
            }
        } else {
            verifyChecksumIfNeeded(cacheFile.readBytes(), map.checksum)
        }
        val text = cacheFile.readText(Charsets.UTF_8).trim()
        if (text.startsWith("[")) {
            org.json.JSONArray(text)
        } else {
            JSONObject(text)
        }
        return cacheFile.absolutePath
    }

    private fun verifyChecksumIfNeeded(bytes: ByteArray, checksum: String?) {
        val expected = checksum?.removePrefix("sha256:")?.takeIf { it.isNotBlank() } ?: return
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        require(actual.equals(expected, ignoreCase = true)) { "checksum 不匹配" }
    }

    private fun publish(topic: String, json: String): Boolean {
        val mqttClient = client
        if (mqttClient == null || !mqttClient.isConnected) {
            scope.launch { tryConnectIfNeeded("发送前补连") }
            return false
        }
        return try {
            val message = MqttMessage(json.toByteArray(Charsets.UTF_8))
            message.qos = QOS
            mqttClient.publish(topic, message)
            true
        } catch (e: Exception) {
            LogUtils.system("MQTT 发布失败：${e.message}")
            false
        }
    }

    private fun newCmdId(): String = "cmd_${System.currentTimeMillis()}"

    private fun nowTimestamp(): String = Instant.ofEpochMilli(System.currentTimeMillis()).toString()

    companion object {
        private const val PROTOCOL_VERSION = "1.0"
        private const val QOS = 1
        private const val HEARTBEAT_TIMEOUT_MS = 3000L
        private val SUPPORTED_CMDS = setOf("start", "stop", "estop", "clear_estop")

        fun topicHeartbeat(productType: String, deviceId: String) = "device/$productType/$deviceId/heartbeat"
        fun topicStatus(productType: String, deviceId: String) = "device/$productType/$deviceId/status"
        fun topicCmdAck(productType: String, deviceId: String) = "device/$productType/$deviceId/cmd_ack"
        fun topicMap(productType: String, deviceId: String) = "device/$productType/$deviceId/map"
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
