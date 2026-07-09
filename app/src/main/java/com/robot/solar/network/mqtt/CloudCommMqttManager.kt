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
    private var client: MqttClient? = null
    private var boundDeviceId: String? = null
    private var boundProductType: String? = null
    private val connecting = AtomicBoolean(false)
    private var reconnectJob: Job? = null

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
        boundDeviceId = deviceId
        boundProductType = productType
        scope.launch { tryConnectIfNeeded("绑定设备 $productType/$deviceId") }
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
                    _deviceOnline.postValue(msg.online == true)
                    LogUtils.device("heartbeat：online=${msg.online}")
                }
                topic.endsWith("/status") -> {
                    val msg = gson.fromJson(payload, StatusMessage::class.java)
                    _status.postValue(msg)
                    msg.batteryPercent?.let {
                        _batteryPercent.postValue(it.toInt().coerceIn(0, 100))
                    }
                    _deviceOnline.postValue(true)
                    LogUtils.device("status：battery=${msg.batteryPercent}, work=${msg.workStatus}")
                }
                topic.endsWith("/cmd_ack") -> {
                    val ack = gson.fromJson(payload, CmdAckMessage::class.java)
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

    /** 低频系统命令：start / stop / pause / resume / estop / clear_estop */
    fun publishCmd(action: String): Boolean {
        val deviceId = boundDeviceId ?: return false
        val productType = boundProductType ?: BuildConfig.MQTT_DEFAULT_PRODUCT_TYPE
        val json = JSONObject()
            .put("version", PROTOCOL_VERSION)
            .put("cmdId", newCmdId())
            .put("deviceId", deviceId)
            .put("productType", productType)
            .put("timestamp", nowTimestamp())
            .put("cmd", action)
            .put("params", JSONObject())
        return publish(topicCmd(productType, deviceId), json.toString())
    }

    /** 遥控速度：线速度单位 cm/s，前进为正；角速度单位 rad/s。 */
    fun publishRemote(linearSpeedCms: Double, angularRadps: Double, durationMs: Int = 300): Boolean {
        val deviceId = boundDeviceId ?: return false
        val productType = boundProductType ?: BuildConfig.MQTT_DEFAULT_PRODUCT_TYPE
        val json = JSONObject()
            .put("version", PROTOCOL_VERSION)
            .put("deviceId", deviceId)
            .put("productType", productType)
            .put("timestamp", nowTimestamp())
            .put("linearSpeedCms", linearSpeedCms)
            .put("angularSpeedRadps", angularRadps)
            .put("durationMs", durationMs)
        return publish(topicRemote(productType, deviceId), json.toString())
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
