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

/**
 * 全局 MQTT 管理：连接公共 HiveMQ 测试 Broker、订阅状态、发布控制指令、断线重连
 *
 * 线程说明：Paho 回调可能在非主线程，状态统一通过 [postValue] 分发到 LiveData。
 */
class MqttManager private constructor(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var client: MqttClient? = null
    private val connecting = AtomicBoolean(false)
    private var reconnectJob: Job? = null

    private val _mqttConnected = MutableLiveData(false)
    val mqttConnected: LiveData<Boolean> = _mqttConnected

    private val _deviceOnline = MutableLiveData<Boolean?>(null)
    val deviceOnline: LiveData<Boolean?> = _deviceOnline

    /** 0-100，null 表示尚未收到远端电量字段 */
    private val _batteryPercent = MutableLiveData<Int?>(null)
    val batteryPercent: LiveData<Int?> = _batteryPercent

    private val _lastRawStatus = MutableLiveData<String>()
    val lastRawStatus: LiveData<String> = _lastRawStatus

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
            LogUtils.system("注册网络监听失败（可忽略，仍可通过页面进入时重连）")
        }
    }

    /**
     * 对外入口：在主页可见时调用，内部幂等防重入
     */
    fun start() {
        scope.launch { tryConnectIfNeeded("应用请求连接") }
    }

    /**
     * 释放资源：Activity onDestroy 时可选调用（Demo 单例常驻进程可不调用）
     */
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
            LogUtils.system("MQTT 尝试连接：$reason")
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
                isAutomaticReconnect = false // 由应用层统一调度，便于写日志

                mqttVersion = MqttConnectOptions.MQTT_VERSION_3_1_1
                userName = MQTT_USERNAME
                password = MQTT_PASSWORD.toCharArray()
            }
            mqttClient.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    _mqttConnected.postValue(true)
                    LogUtils.system("MQTT connectComplete（reconnect=$reconnect） uri=$serverURI")
                }

                override fun connectionLost(cause: Throwable?) {
                    _mqttConnected.postValue(false)
                    LogUtils.system("MQTT 连接丢失：${cause?.message ?: "未知原因"}")
                    scheduleReconnect("connectionLost")
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    val payload = message?.toString().orEmpty()
                    _lastRawStatus.postValue(payload)
                    parseRobotStatus(payload)
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {
                    // 发布完成（Demo 可不处理）
                }
            })
            mqttClient.connect(options)
            client = mqttClient
            _mqttConnected.postValue(true)
            subscribeTopics(mqttClient)
        } catch (e: MqttException) {
            _mqttConnected.postValue(false)
            LogUtils.system("MQTT 连接异常：${e.message}")
            scheduleReconnect("connectFailed")
        } catch (e: Exception) {
            _mqttConnected.postValue(false)
            LogUtils.system("MQTT 未知异常：${e.message}")
            scheduleReconnect("connectFailed")
        }
    }

    private fun subscribeTopics(mqttClient: MqttClient) {
        try {
            // 使用同步订阅；消息回调在 [MqttCallbackExtended.messageArrived]
            mqttClient.subscribe(TOPIC_STATUS, QOS)
            LogUtils.system("已订阅主题：$TOPIC_STATUS")
        } catch (e: Exception) {
            LogUtils.system("订阅状态主题失败：${e.message}")
        }
    }

    private fun parseRobotStatus(payload: String) {
        try {
            val obj = JSONObject(payload)
            if (obj.has("online")) {
                _deviceOnline.postValue(obj.getBoolean("online"))
            }
            if (obj.has("battery")) {
                val v = obj.getInt("battery").coerceIn(0, 100)
                _batteryPercent.postValue(v)
            }
        } catch (_: Exception) {
            // 公共 Broker 上可能存在非 JSON 噪声消息，忽略即可
        }
    }

    private fun scheduleReconnect(reason: String) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(3000)
            LogUtils.system("MQTT 自动重连调度：$reason")
            tryConnectIfNeeded("自动重连")
        }
    }

    /**
     * 发布标准 JSON 控制指令到 [TOPIC_CMD]
     * @return 是否发布成功（不代表设备一定执行）
     */
    fun publishCommandJson(cmd: String): Boolean {
        val mqttClient = client
        if (mqttClient == null || !mqttClient.isConnected) {
            LogUtils.system("MQTT 未连接，指令未发送：$cmd")
            scope.launch { tryConnectIfNeeded("发送前补连") }
            return false
        }
        return try {
            val json = JSONObject()
                .put("cmd", cmd)
                .put("deviceId", DEVICE_ID)
                .put("ts", System.currentTimeMillis())
                .toString()
            val message = MqttMessage(json.toByteArray(Charsets.UTF_8))
            message.qos = QOS
            message.isRetained = false
            mqttClient.publish(TOPIC_CMD, message)
            true
        } catch (e: Exception) {
            LogUtils.system("MQTT 发布失败：${e.message}")
            false
        }
    }

    companion object {
        private const val SERVER_URI = "tcp://47.103.157.213:1883"
        private const val MQTT_USERNAME = "app_user_001"
        private const val MQTT_PASSWORD = "8zmJV8ZHDL/zZ4rLqYbWAYQ8ogsShiz8"

        private const val CLIENT_PREFIX = "solar_demo_"
        private const val DEVICE_ID = "solar_demo"

        /** 发布控制指令 */
        const val TOPIC_CMD: String = "solarbot/robot/cmd"

        /** 订阅设备状态 */
        const val TOPIC_STATUS: String = "solarbot/robot/status"

        private const val QOS: Int = 1

        @Volatile
        private var INSTANCE: MqttManager? = null

        fun getInstance(context: Context): MqttManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MqttManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
