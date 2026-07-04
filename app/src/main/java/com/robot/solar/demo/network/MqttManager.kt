package com.robot.solar.demo.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.robot.solar.demo.utils.LogUtils
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
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject

class MqttManager private constructor(private val appContext: Context) {

    // MQTT 连接与重连状态。后续只改 topic/payload 时，一般不需要动这一组字段。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var client: MqttClient? = null
    private val connecting = AtomicBoolean(false)
    private var reconnectJob: Job? = null

    private val _mqttConnected = MutableLiveData(false)
    val mqttConnected: LiveData<Boolean> = _mqttConnected

    private val _deviceOnline = MutableLiveData<Boolean?>(null)
    val deviceOnline: LiveData<Boolean?> = _deviceOnline

    private val _batteryPercent = MutableLiveData<Int?>(null)
    val batteryPercent: LiveData<Int?> = _batteryPercent

    private val _lastRawStatus = MutableLiveData<String>()
    val lastRawStatus: LiveData<String> = _lastRawStatus

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch { tryConnectIfNeeded("network available") }
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
            LogUtils.system("Register network callback failed, ignored")
        }
    }

    fun start() {
        scope.launch { tryConnectIfNeeded("app requested connect") }
    }

    fun shutdown() {
        reconnectJob?.cancel()
        scope.launch {
            try {
                client?.disconnect()
                client?.close()
            } catch (_: Exception) {
            } finally {
                client = null
                _mqttConnected.postValue(false)
            }
        }
    }

    private suspend fun tryConnectIfNeeded(reason: String) {
        if (connecting.get()) return
        if (client?.isConnected == true) return
        if (!connecting.compareAndSet(false, true)) return
        try {
            withContext(Dispatchers.IO) {
                connectInternal(reason)
            }
        } finally {
            connecting.set(false)
        }
    }

    private fun connectInternal(reason: String) {
        try {
            LogUtils.system("MQTT connecting: $reason")
            try {
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
            } catch (_: Exception) {
            }
            client = null

            val persistence = MemoryPersistence()
            val serverUri = SERVER_URI
            val clientId = CLIENT_PREFIX + UUID.randomUUID().toString().take(8)
            val mqttClient = MqttClient(serverUri, clientId, persistence)
            val options = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 10
                keepAliveInterval = 20
                isAutomaticReconnect = false

                mqttVersion = MqttConnectOptions.MQTT_VERSION_3_1_1
                userName = MQTT_USERNAME
                password = MQTT_PASSWORD.toCharArray()
            }
            mqttClient.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    _mqttConnected.postValue(true)
                    LogUtils.system("MQTT connectComplete reconnect=$reconnect uri=$serverURI")
                }

                override fun connectionLost(cause: Throwable?) {
                    _mqttConnected.postValue(false)
                    LogUtils.system("MQTT connection lost: ${cause?.message ?: "unknown"}")
                    scheduleReconnect("connectionLost")
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    val payload = message?.toString().orEmpty()
                    _lastRawStatus.postValue(payload)
                    LogUtils.system("Received MQTT message topic=$topic payload=$payload")
                    parseRobotMessage(topic.orEmpty(), payload)
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {
                }
            })
            mqttClient.connect(options)
            client = mqttClient
            _mqttConnected.postValue(true)
            subscribeTopics(mqttClient)
        } catch (e: MqttException) {
            _mqttConnected.postValue(false)
            LogUtils.system("MQTT connect exception: ${e.message}")
            scheduleReconnect("connectFailed")
        } catch (e: Exception) {
            _mqttConnected.postValue(false)
            LogUtils.system("MQTT unknown exception: ${e.message}")
            scheduleReconnect("connectFailed")
        }
    }

    private fun subscribeTopics(mqttClient: MqttClient) {
        try {
            // App 需要接收机器人端消息时，在这里加入新的订阅 topic。
            // 注意：topics 和 qos 两个数组长度必须保持一致。
            // 新增订阅后，也要在 parseRobotMessage() 的 when(topic) 中加入对应解析分支。
            val topics = arrayOf(
                TOPIC_TELEMETRY,
                TOPIC_EVENT,
                TOPIC_CONNECTION
            )
            val qos = intArrayOf(QOS, QOS, QOS)

            mqttClient.subscribe(topics, qos)
            LogUtils.system("Subscribed MQTT topics: ${topics.joinToString()}")
        } catch (e: Exception) {
            LogUtils.system("Subscribe MQTT topics failed: ${e.message}")
        }
    }

    private fun parseRobotMessage(topic: String, payload: String) {
        try {
            val obj = JSONObject(payload)

            // 所有机器人端上报消息都先校验协议版本和 device_id，避免误处理其他设备或旧协议消息。
            val schema = obj.optString("schema")
            val deviceId = obj.optString("device_id")
            val type = obj.optString("type")

            if (schema != SCHEMA) {
                LogUtils.system("Ignore MQTT message: schema mismatch schema=$schema")
                return
            }

            if (deviceId != DEVICE_ID) {
                LogUtils.system("Ignore MQTT message: device_id mismatch device_id=$deviceId")
                return
            }

            // 订阅 topic 的分发入口：新增/删除订阅 topic 时，优先同步维护这里。
            when (topic) {
                TOPIC_TELEMETRY -> parseTelemetry(obj)
                TOPIC_CONNECTION -> parseConnection(obj)
                TOPIC_EVENT -> parseEvent(obj)
                else -> LogUtils.system("Received unregistered topic message topic=$topic type=$type")
            }
        } catch (e: Exception) {
            LogUtils.system("Parse MQTT message failed: ${e.message}")
        }
    }

    // telemetry topic 的 payload 解析。这里只更新当前 UI 已经使用的在线状态和电量字段。
    private fun parseTelemetry(obj: JSONObject) {
        val status = obj.optJSONObject("status") ?: return

        if (status.has("battery_percent")) {
            val battery = status.optDouble("battery_percent", -1.0)
                .toInt()
                .coerceIn(0, 100)
            _batteryPercent.postValue(battery)
        }

        if (status.has("fcu_connected")) {
            _deviceOnline.postValue(status.optBoolean("fcu_connected"))
        }

        LogUtils.device("Received telemetry: $obj")
    }

    // connection topic 的 payload 解析。机器人端连接状态字段变化时，主要改这里。
    private fun parseConnection(obj: JSONObject) {
        val state = obj.optString("state")

        when (state) {
            "online" -> _deviceOnline.postValue(true)
            "offline", "connection_broken" -> _deviceOnline.postValue(false)
        }

        LogUtils.device("Received connection: state=$state")
    }

    // event topic 暂时只记录日志。后续如果要展示告警/任务事件，可以从这里扩展。
    private fun parseEvent(obj: JSONObject) {
        LogUtils.device("Received event: $obj")
    }

    private fun scheduleReconnect(reason: String) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(3000)
            LogUtils.system("MQTT auto reconnect scheduled: $reason")
            tryConnectIfNeeded("auto reconnect")
        }
    }

    // 保留给现有 UI 调用的入口：UI 仍传入简单命令，这里负责转换为机器人端 API payload。
    // 如果后续 UI 新增按钮，优先在这个 when 中添加映射，不需要改 UI 的调用方式。
    fun publishCommandJson(cmd: String): Boolean {
        return when (cmd) {
            "forward" -> publishRemote(
                linearVelocityMps = DEFAULT_LINEAR_SPEED_MPS,
                angularVelocityRadps = 0.0
            )

            "backward" -> publishRemote(
                linearVelocityMps = -DEFAULT_LINEAR_SPEED_MPS,
                angularVelocityRadps = 0.0
            )

            "turn_left" -> publishRemote(
                linearVelocityMps = 0.0,
                angularVelocityRadps = DEFAULT_ANGULAR_SPEED_RADPS
            )

            "turn_right" -> publishRemote(
                linearVelocityMps = 0.0,
                angularVelocityRadps = -DEFAULT_ANGULAR_SPEED_RADPS
            )

            "stop" -> publishRemote(
                linearVelocityMps = 0.0,
                angularVelocityRadps = 0.0
            )

            "emergency_stop" -> publishCmd("estop")

            else -> {
                LogUtils.system("Unknown control command: $cmd")
                false
            }
        }
    }

    // 发布到 device/{device_id}/cmd：适合急停、模式切换等离散动作命令。
    fun publishCmd(action: String): Boolean {
        val payload = JSONObject()
            .put("schema", SCHEMA)
            .put("type", "cmd")
            .put("cmd_id", nextCmdId())
            .put("device_id", DEVICE_ID)
            .put("timestamp_ms", System.currentTimeMillis())
            .put("action", action)
            .toString()

        return publishJson(TOPIC_CMD, payload)
    }

    // 发布到 device/{device_id}/remote：适合方向控制、速度控制等遥控类命令。
    fun publishRemote(
        linearVelocityMps: Double,
        angularVelocityRadps: Double,
        durationMs: Int = DEFAULT_REMOTE_DURATION_MS
    ): Boolean {
        val payload = JSONObject()
            .put("schema", SCHEMA)
            .put("type", "remote")
            .put("cmd_id", nextCmdId())
            .put("device_id", DEVICE_ID)
            .put("timestamp_ms", System.currentTimeMillis())
            .put("linear_velocity_mps", linearVelocityMps)
            .put("angular_velocity_radps", angularVelocityRadps)
            .put("duration_ms", durationMs)
            .toString()

        return publishJson(TOPIC_REMOTE, payload)
    }

    // 发布到 device/{device_id}/config：适合参数下发。UI 目前未调用，预留给后续配置页面。
    fun publishConfig(
        paramIndex: Int,
        paramType: String,
        value: Number
    ): Boolean {
        val payload = JSONObject()
            .put("schema", SCHEMA)
            .put("type", "config")
            .put("cmd_id", nextCmdId())
            .put("device_id", DEVICE_ID)
            .put("timestamp_ms", System.currentTimeMillis())
            .put("param_index", paramIndex)
            .put("param_type", paramType)
            .put("value", value)
            .toString()

        return publishJson(TOPIC_CONFIG, payload)
    }

    // 所有 MQTT 发布最终都走这里，统一处理连接检查、QoS、retain 和日志。
    // 新增发布 topic 时，建议新增一个 publishXxx() 组 payload，然后调用 publishJson(topic, payload)。
    private fun publishJson(topic: String, payload: String): Boolean {
        val mqttClient = client
        if (mqttClient == null || !mqttClient.isConnected) {
            LogUtils.system("MQTT not connected, message not sent topic=$topic payload=$payload")
            scope.launch { tryConnectIfNeeded("connect before publish") }
            return false
        }

        return try {
            val message = MqttMessage(payload.toByteArray(Charsets.UTF_8))
            message.qos = QOS
            message.isRetained = false
            mqttClient.publish(topic, message)
            LogUtils.system("MQTT published topic=$topic payload=$payload")
            true
        } catch (e: Exception) {
            LogUtils.system("MQTT publish failed topic=$topic error=${e.message}")
            false
        }
    }

    private fun nextCmdId(): String {
        return "app-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
    }

    companion object {
        // Broker 和认证配置。真实密码不要提交到公开仓库，建议本地填入或后续接入安全配置。
        private const val SERVER_URI = "tcp://47.103.157.213:1883"
        private const val MQTT_USERNAME = "app_user_001"
        private const val MQTT_PASSWORD = ""

        // 设备与协议配置。真实机器人 device_id 确认后，只需要改 DEVICE_ID 这一行。
        private const val CLIENT_PREFIX = "solar_app_"
        private const val DEVICE_ID = "test_device_001"
        private const val SCHEMA = "vgsolar.cloud_comm.v1"

        // App 订阅的机器人上报 topic。修改这里后，同步维护 subscribeTopics() 和 parseRobotMessage()。
        const val TOPIC_TELEMETRY: String = "device/$DEVICE_ID/telemetry"
        const val TOPIC_EVENT: String = "device/$DEVICE_ID/event"
        const val TOPIC_CONNECTION: String = "device/$DEVICE_ID/connection"

        // App 发布给机器人的控制 topic。修改这里后，同步维护对应 publishXxx() 方法。
        const val TOPIC_CMD: String = "device/$DEVICE_ID/cmd"
        const val TOPIC_REMOTE: String = "device/$DEVICE_ID/remote"
        const val TOPIC_CONFIG: String = "device/$DEVICE_ID/config"

        // MQTT 消息质量和 UI 简单命令的默认遥控参数。
        private const val QOS: Int = 1
        private const val DEFAULT_LINEAR_SPEED_MPS = 0.15
        private const val DEFAULT_ANGULAR_SPEED_RADPS = 0.5
        private const val DEFAULT_REMOTE_DURATION_MS = 300

        @Volatile
        private var INSTANCE: MqttManager? = null

        fun getInstance(context: Context): MqttManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MqttManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
