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

/**
 * cloud_comm 正式 MQTT 管理：device/{id}/telemetry|event|connection 与 cmd|remote|config
 */
class CloudCommMqttManager private constructor(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private var client: MqttClient? = null
    private var boundDeviceId: String? = null
    private val connecting = AtomicBoolean(false)
    private var reconnectJob: Job? = null

    private val _mqttConnected = MutableLiveData(false)
    val mqttConnected: LiveData<Boolean> = _mqttConnected

    private val _deviceOnline = MutableLiveData<Boolean?>(null)
    val deviceOnline: LiveData<Boolean?> = _deviceOnline

    private val _batteryPercent = MutableLiveData<Int?>(null)
    val batteryPercent: LiveData<Int?> = _batteryPercent

    private val _telemetry = MutableLiveData<TelemetryMessage?>(null)
    val telemetry: LiveData<TelemetryMessage?> = _telemetry

    private val _lastCmdFeedback = MutableLiveData<String?>(null)
    val lastCmdFeedback: LiveData<String?> = _lastCmdFeedback

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
    fun start(deviceId: String) {
        boundDeviceId = deviceId
        scope.launch { tryConnectIfNeeded("绑定设备 $deviceId") }
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
            subscribeTopics(mqttClient, deviceId)
        } catch (e: Exception) {
            _mqttConnected.postValue(false)
            LogUtils.system("MQTT 连接失败：${e.message}")
            scheduleReconnect()
        }
    }

    private fun subscribeTopics(mqttClient: MqttClient, deviceId: String) {
        val topics = arrayOf(
            topicTelemetry(deviceId),
            topicEvent(deviceId),
            topicConnection(deviceId)
        )
        val qos = IntArray(3) { QOS }
        mqttClient.subscribe(topics, qos)
        LogUtils.system("已订阅 device/$deviceId/* 上行主题")
    }

    private fun handleMessage(topic: String, payload: String) {
        try {
            val obj = JSONObject(payload)
            if (!isValidEnvelope(obj, topic)) return

            when {
                topic.endsWith("/telemetry") -> {
                    val msg = gson.fromJson(payload, TelemetryMessage::class.java)
                    _telemetry.postValue(msg)
                    msg.status?.batteryPercent?.let {
                        _batteryPercent.postValue(it.toInt().coerceIn(0, 100))
                    }
                    msg.status?.fcuConnected?.let { _deviceOnline.postValue(it) }
                    LogUtils.device("telemetry：battery=${msg.status?.batteryPercent}, fcu=${msg.status?.fcuConnected}")
                }
                topic.endsWith("/connection") -> {
                    val msg = gson.fromJson(payload, ConnectionMessage::class.java)
                    val online = msg.state == "online"
                    _deviceOnline.postValue(online)
                    LogUtils.device("connection：${msg.state}")
                }
                topic.endsWith("/event") -> {
                    if (obj.optString("type") == "cmd_feedback") {
                        val fb = gson.fromJson(payload, CmdFeedbackEvent::class.java)
                        val text = when (fb.execStatus) {
                            1 -> "指令执行成功"
                            2 -> "指令执行失败"
                            else -> "指令反馈"
                        }
                        _lastCmdFeedback.postValue(text)
                        LogUtils.device("cmd_feedback：$text")
                    }
                }
            }
        } catch (e: Exception) {
            LogUtils.system("MQTT 消息解析失败：${e.message}")
        }
    }

    /**
     * 所有 cloud_comm 上行消息必须先通过协议头校验。
     * 这样可避免公共/联调 Broker 上其他设备或旧协议消息影响当前设备 UI。
     */
    private fun isValidEnvelope(obj: JSONObject, topic: String): Boolean {
        val schema = obj.optString("schema")
        if (schema != SCHEMA) {
            LogUtils.system("忽略 MQTT 消息：schema 不匹配 topic=$topic schema=$schema")
            return false
        }

        val deviceId = obj.optString("device_id")
        val expectedDeviceId = boundDeviceId
        if (!expectedDeviceId.isNullOrBlank() && deviceId != expectedDeviceId) {
            LogUtils.system("忽略 MQTT 消息：device_id 不匹配 expected=$expectedDeviceId actual=$deviceId")
            return false
        }

        val type = obj.optString("type")
        val expectedType = when {
            topic.endsWith("/telemetry") -> "telemetry"
            topic.endsWith("/connection") -> "connection"
            topic.endsWith("/event") -> null
            else -> null
        }
        if (expectedType != null && type != expectedType) {
            LogUtils.system("忽略 MQTT 消息：type 不匹配 topic=$topic type=$type")
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

    /** 低频系统命令：start / stop / pause / resume / estop / clear_estop */
    fun publishCmd(action: String): Boolean {
        val deviceId = boundDeviceId ?: return false
        val json = JSONObject()
            .put("schema", SCHEMA)
            .put("type", "cmd")
            .put("cmd_id", newCmdId())
            .put("device_id", deviceId)
            .put("timestamp_ms", System.currentTimeMillis())
            .put("action", action)
        return publish(topicCmd(deviceId), json.toString())
    }

    /** 遥控速度：前进为正线速度，左转为正角速度 */
    fun publishRemote(linearMps: Double, angularRadps: Double, durationMs: Int = 300): Boolean {
        val deviceId = boundDeviceId ?: return false
        val json = JSONObject()
            .put("schema", SCHEMA)
            .put("type", "remote")
            .put("cmd_id", newCmdId())
            .put("device_id", deviceId)
            .put("timestamp_ms", System.currentTimeMillis())
            .put("linear_velocity_mps", linearMps)
            .put("angular_velocity_radps", angularRadps)
            .put("duration_ms", durationMs)
        return publish(topicRemote(deviceId), json.toString())
    }

    /** 参数下发：用于后续清洁模式、行数限制、场景参数等 config 接口联调。 */
    fun publishConfig(paramIndex: Int, paramType: String, value: Number): Boolean {
        val deviceId = boundDeviceId ?: return false
        val json = JSONObject()
            .put("schema", SCHEMA)
            .put("type", "config")
            .put("cmd_id", newCmdId())
            .put("device_id", deviceId)
            .put("timestamp_ms", System.currentTimeMillis())
            .put("param_index", paramIndex)
            .put("param_type", paramType)
            .put("value", value)
        return publish(topicConfig(deviceId), json.toString())
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

    private fun newCmdId(): String = "cmd-${System.currentTimeMillis()}"

    companion object {
        private const val SCHEMA = "vgsolar.cloud_comm.v1"
        private const val QOS = 1

        fun topicTelemetry(deviceId: String) = "device/$deviceId/telemetry"
        fun topicEvent(deviceId: String) = "device/$deviceId/event"
        fun topicConnection(deviceId: String) = "device/$deviceId/connection"
        fun topicCmd(deviceId: String) = "device/$deviceId/cmd"
        fun topicRemote(deviceId: String) = "device/$deviceId/remote"
        fun topicConfig(deviceId: String) = "device/$deviceId/config"

        @Volatile
        private var INSTANCE: CloudCommMqttManager? = null

        fun getInstance(context: Context): CloudCommMqttManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CloudCommMqttManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
