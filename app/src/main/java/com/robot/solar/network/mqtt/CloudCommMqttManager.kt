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
import com.robot.solar.entity.LogCategory
import com.robot.solar.entity.LogDirection
import com.robot.solar.entity.LogSeverity
import com.robot.solar.entity.LogSource
import com.robot.solar.entity.StructuredLogDraft
import com.robot.solar.logging.AppLogPolicy
import com.robot.solar.logging.StatusLogSnapshot
import com.robot.solar.utils.LogUtils
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
    private var client: MqttClient? = null
    private var boundDeviceId: String? = null
    private var boundProductType: String? = null
    private val connecting = AtomicBoolean(false)
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var lastHeartbeatMillis: Long? = null
    @Volatile
    private var loggedOnlineState: Boolean? = null
    private var loggedStatusSnapshot: StatusLogSnapshot? = null

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

    private val _pose = MutableLiveData<PoseMessage?>(null)
    val pose: LiveData<PoseMessage?> = _pose

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch { tryConnectIfNeeded("网络恢复") }
        }

        override fun onLost(network: Network) {
            _mqttConnected.postValue(false)
            markRobotOffline(clearHeartbeat = true)
            LogUtils.connection(
                eventType = "network_lost",
                summary = "网络连接已丢失",
                result = "disconnected",
                severity = LogSeverity.WARNING
            )
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
            LogUtils.connection(
                eventType = "network_monitor_failed",
                summary = "注册网络监听失败",
                result = "failed",
                severity = LogSeverity.WARNING
            )
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
        scope.launch { tryConnectIfNeeded("绑定设备 $productType/$deviceId") }
    }

    fun shutdown() {
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
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
        recordOnlineTransition(false)
        loggedStatusSnapshot = null
        _deviceOnline.postValue(false)
        _batteryPercent.postValue(null)
        _status.postValue(null)
        _missionState.postValue(MissionState())
        _pose.postValue(null)
    }

    private fun clearDeviceState() {
        lastHeartbeatMillis = null
        loggedOnlineState = null
        loggedStatusSnapshot = null
        _lastHeartbeatAt.postValue(null)
        _deviceOnline.postValue(null)
        _batteryPercent.postValue(null)
        _status.postValue(null)
        _missionState.postValue(MissionState())
        _lastCmdAck.postValue(null)
        _lastCmdFeedback.postValue(null)
        _pose.postValue(null)
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
            LogUtils.connection(
                eventType = "mqtt_connecting",
                summary = "正在连接 MQTT：$reason",
                result = "connecting"
            )
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
                    LogUtils.connection(
                        eventType = "mqtt_connected",
                        summary = if (reconnect) "MQTT 已重新连接" else "MQTT 已连接",
                        result = "connected"
                    )
                }

                override fun connectionLost(cause: Throwable?) {
                    _mqttConnected.postValue(false)
                    markRobotOffline(clearHeartbeat = true)
                    LogUtils.connection(
                        eventType = "mqtt_disconnected",
                        summary = "设备连接已断开，正在尝试恢复",
                        result = "disconnected",
                        severity = LogSeverity.WARNING,
                        detailJson = cause?.message?.let { JSONObject().put("reason", it).toString() }
                    )
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
            LogUtils.connection(
                eventType = "mqtt_connect_failed",
                summary = "设备连接失败，请检查网络",
                result = "failed",
                severity = LogSeverity.ERROR,
                detailJson = e.message?.let { JSONObject().put("reason", it).toString() }
            )
            scheduleReconnect()
        }
    }

    private fun subscribeTopics(mqttClient: MqttClient, productType: String, deviceId: String) {
        val topics = arrayOf(
            topicHeartbeat(productType, deviceId),
            topicStatus(productType, deviceId),
            topicCmdAck(productType, deviceId),
            topicPose(productType, deviceId)
        )
        mqttClient.subscribe(topics, IntArray(topics.size) { COMMAND_QOS })
        LogUtils.connection(
            eventType = "mqtt_subscribed",
            summary = "已订阅设备上行主题",
            result = "success",
            detailJson = JSONObject()
                .put("prefix", "device/$productType/$deviceId")
                .put("topics", "heartbeat,status,cmd_ack,pose")
                .toString()
        )
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
                    recordOnlineTransition(msg.online == true)
                }
                topic.endsWith("/status") -> {
                    val msg = gson.fromJson(payload, StatusMessage::class.java)
                    _status.postValue(msg)
                    _missionState.postValue(
                        MissionState(
                            missionId = msg.missionId,
                            rootMissionId = msg.rootMissionId,
                            taskKind = msg.taskKind,
                            runState = msg.runState,
                            orchestrationState = msg.orchestrationState,
                            taskStackDepth = msg.taskStackDepth,
                            interruptionReason = msg.interruptionReason,
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
                    recordStatusChanges(topic, msg)
                }
                topic.endsWith("/cmd_ack") -> {
                    val ack = gson.fromJson(payload, CmdAckMessage::class.java)
                    _lastCmdAck.postValue(ack)
                    val text = when (ack.ackStatus) {
                        "success" -> "机器人已受理命令"
                        "failed" -> "机器人拒绝了命令"
                        else -> "收到设备操作反馈"
                    }
                    _lastCmdFeedback.postValue(text)
                }
                topic.endsWith("/pose") -> {
                    val pose = gson.fromJson(payload, PoseMessage::class.java)
                    _pose.postValue(pose)
                }
            }
        } catch (e: Exception) {
            protocolWarning(
                eventType = "message_parse_failed",
                summary = "收到无法识别的设备消息",
                topic = topic,
                detail = e.message
            )
        }
    }

    private fun isValidEnvelope(obj: JSONObject, topic: String): Boolean {
        val version = obj.optString("version")
        if (version != PROTOCOL_VERSION) {
            protocolWarning(
                "protocol_version_mismatch",
                "忽略 MQTT 消息：协议版本不匹配",
                topic,
                "expected=$PROTOCOL_VERSION actual=$version"
            )
            return false
        }

        val productType = obj.optString("productType")
        val expectedProductType = boundProductType
        if (!expectedProductType.isNullOrBlank() && productType != expectedProductType) {
            protocolWarning(
                "product_type_mismatch",
                "忽略 MQTT 消息：设备类型不匹配",
                topic,
                "expected=$expectedProductType actual=$productType"
            )
            return false
        }

        val deviceId = obj.optString("deviceId")
        val expectedDeviceId = boundDeviceId
        if (!expectedDeviceId.isNullOrBlank() && deviceId != expectedDeviceId) {
            protocolWarning(
                "device_id_mismatch",
                "忽略 MQTT 消息：设备编号不匹配",
                topic,
                "expected=$expectedDeviceId actual=$deviceId"
            )
            return false
        }

        return true
    }

    private fun recordOnlineTransition(online: Boolean) {
        val summary = AppLogPolicy.heartbeatSummary(loggedOnlineState, online) ?: return
        loggedOnlineState = online
        LogUtils.record(
            StructuredLogDraft(
                source = LogSource.ROBOT,
                category = LogCategory.CONNECTION,
                eventType = if (online) "device_online" else "device_offline",
                severity = if (online) LogSeverity.INFO else LogSeverity.WARNING,
                direction = LogDirection.UPSTREAM,
                topic = boundProductType?.let { product ->
                    boundDeviceId?.let { device -> topicHeartbeat(product, device) }
                },
                result = if (online) "online" else "offline",
                summary = summary,
                dedupeKey = "device_online"
            )
        )
    }

    private fun recordStatusChanges(topic: String, msg: StatusMessage) {
        val current = StatusLogSnapshot(
            missionId = msg.missionId,
            runState = msg.runState,
            operationalMode = msg.operationalMode,
            safetyState = msg.safetyState,
            deviceStatus = msg.deviceStatus,
            movementStatus = msg.movementStatus,
            batteryPercent = msg.batteryPercent?.toInt(),
            errorCode = msg.missionErrorCode,
            errorMessage = msg.errorMessage,
            rootMissionId = msg.rootMissionId,
            taskKind = msg.taskKind,
            orchestrationState = msg.orchestrationState,
            taskStackDepth = msg.taskStackDepth,
            interruptionReason = msg.interruptionReason
        )
        val details = JSONObject()
            .put("missionId", msg.missionId)
            .put("rootMissionId", msg.rootMissionId)
            .put("taskKind", msg.taskKind)
            .put("runState", msg.runState)
            .put("orchestrationState", msg.orchestrationState)
            .put("taskStackDepth", msg.taskStackDepth)
            .put("interruptionReason", msg.interruptionReason)
            .put("operationalMode", msg.operationalMode)
            .put("safetyState", msg.safetyState)
            .put("phase", msg.phase)
            .put("activeAction", msg.activeAction)
            .put("errorCode", msg.missionErrorCode)
            .put("errorMessage", msg.errorMessage)
            .toString()
        AppLogPolicy.statusChanges(loggedStatusSnapshot, current).forEach { change ->
            LogUtils.record(
                StructuredLogDraft(
                    source = LogSource.ROBOT,
                    category = change.category,
                    eventType = change.eventType,
                    severity = change.severity,
                    direction = LogDirection.UPSTREAM,
                    topic = topic,
                    missionId = msg.rootMissionId?.takeIf { it.isNotBlank() } ?: msg.missionId,
                    action = msg.activeAction,
                    result = change.result,
                    summary = change.summary,
                    detailJson = details
                )
            )
        }
        loggedStatusSnapshot = current
    }

    private fun protocolWarning(
        eventType: String,
        summary: String,
        topic: String,
        detail: String?
    ) {
        LogUtils.record(
            StructuredLogDraft(
                source = LogSource.MQTT,
                category = LogCategory.SYSTEM,
                eventType = eventType,
                severity = LogSeverity.WARNING,
                direction = LogDirection.UPSTREAM,
                topic = topic,
                summary = summary,
                detailJson = detail?.let { JSONObject().put("reason", it).toString() },
                dedupeKey = "$eventType:$topic"
            )
        )
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
            LogUtils.record(
                StructuredLogDraft(
                    source = LogSource.APP,
                    category = LogCategory.COMMAND,
                    eventType = "unknown_command_rejected",
                    severity = LogSeverity.WARNING,
                    action = action,
                    result = "rejected",
                    summary = "拒绝发送未知命令：$action"
                )
            )
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
            LogUtils.connection(
                eventType = "mqtt_publish_failed",
                summary = "MQTT 消息发送失败",
                result = "failed",
                severity = LogSeverity.ERROR,
                detailJson = JSONObject()
                    .put("topic", topic)
                    .put("reason", e.message)
                    .toString()
            )
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

        fun shutdownIfInitialized() {
            INSTANCE?.shutdown()
        }
    }
}
