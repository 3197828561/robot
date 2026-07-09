package com.robot.solar.network.mqtt

/** 第一版 APP 与 Robot 通信协议：device/{productType}/{deviceId}/{topicType}。 */
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
    /** 兼容 UI 展示的可选扩展字段，硬件第一版未强制要求。 */
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
    val mapId: String?,
    val mapName: String?,
    val mapVersion: Int?,
    val mapJsonUrl: String?,
    val fileSizeBytes: Int?,
    val checksum: String?
)

data class DeviceTopicIdentity(
    val productType: String,
    val deviceId: String
)
