package com.robot.solar.network.mqtt

/** 第二版 App 与 Robot 通信协议：device/{productType}/{deviceId}/{topicType}。 */
data class HeartbeatMessage(
    val version: String?,
    val deviceId: String?,
    val productType: String?,
    val timestamp: String?,
    val online: Boolean?
)

data class StatusMessage(
    val version: String?,
    val deviceId: String?,
    val productType: String?,
    val timestamp: String?,
    val workStatus: String?,
    val controlMode: String?,
    val batteryPercent: Double?,
    val linearSpeedCms: Double?,
    val angularSpeedRadps: Double?,
    val deviceStatus: String?,
    val movementStatus: String?,
    /** 设备状态扩展字段；地图定位统一由 PoseMessage 提供。 */
    val yawDeg: Double?,
    val pitchDeg: Double?,
    val temperatureC: Double?,
    val totalMileageM: Double?,
    val cleanedRows: Int?,
    val pressureKpa: Double?,
    val antiFallLeftM: Double?,
    val antiFallRightM: Double?
)

data class CmdAckMessage(
    val version: String?,
    val deviceId: String?,
    val productType: String?,
    val timestamp: String?,
    val cmdId: String?,
    val cmd: String?,
    val ackStatus: String?,
    val message: String?,
    val errorCode: String?
)

data class MapMessage(
    val version: String?,
    val deviceId: String?,
    val productType: String?,
    val timestamp: String?,
    val mapId: Long?,
    val mapName: String?,
    val mapVersion: Int?,
    val mapJsonUrl: String?,
    val fileSizeBytes: Long?,
    val checksum: String?
)

data class PoseMessage(
    val version: String?,
    val deviceId: String?,
    val productType: String?,
    val timestamp: String?,
    val mapId: Long?,
    val mapVersion: Int?,
    val blockId: Long?,
    val cellId: Long?,
    val cellRow: Int?,
    val cellCol: Int?,
    val innerRow: Int?,
    val innerCol: Int?,
    val headingCode: Int?,
    val heading: String?
)

data class DeviceTopicIdentity(
    val productType: String,
    val deviceId: String
)

data class CmdMessage(
    val version: String,
    val cmdId: String,
    val deviceId: String,
    val productType: String,
    val timestamp: String,
    val cmd: String,
    val params: Map<String, Any?> = emptyMap()
)

data class RemoteMessage(
    val version: String,
    val deviceId: String,
    val productType: String,
    val timestamp: String,
    val linearSpeedCms: Double,
    val angularSpeedRadps: Double,
    val durationMs: Int
)

data class CommandPublishResult(
    val published: Boolean,
    val cmdId: String?,
    val cmd: String
)

data class CommandUiState(
    val cmdId: String?,
    val cmd: String?,
    val status: CommandStatus,
    val message: String? = null,
    val errorCode: String? = null,
    val timestampMillis: Long = System.currentTimeMillis()
)

enum class CommandStatus {
    IDLE,
    SENDING,
    SUCCESS,
    FAILED,
    TIMEOUT,
    CONNECTION_LOST
}

enum class MapLoadStatus {
    NO_MAP,
    DOWNLOADING,
    READY,
    FAILED
}

data class MapUiState(
    val status: MapLoadStatus = MapLoadStatus.NO_MAP,
    val message: String = "暂无地图",
    val map: MapMessage? = null,
    val cachePath: String? = null,
    val pvMap: com.robot.solar.map.PvMap? = null
)
