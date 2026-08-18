package com.robot.solar.network.mqtt

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

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
    val antiFallRightM: Double?,
    val missionId: String?,
    val rootMissionId: String?,
    val taskKind: String?,
    val runState: String?,
    val orchestrationState: String?,
    val taskStackDepth: Int?,
    val interruptionReason: String?,
    val operationalMode: String?,
    val safetyState: String?,
    val phase: String?,
    val activeAction: String?,
    val waypointIndex: Int?,
    val waypointCount: Int?,
    @SerializedName("errorCode") val missionErrorCode: Int?,
    val errorRetryable: Boolean?,
    val errorSource: String?,
    val errorMessage: String?
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

data class PoseMessage(
    val version: String?,
    val deviceId: String?,
    val productType: String?,
    val timestamp: String?,
    val mapId: Long?,
    val mapVersion: Long?,
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

data class PreparedCommand(
    val cmdId: String,
    val cmd: String,
    val payload: String
)

data class CommandPayload(
    val version: String,
    val cmdId: String,
    val deviceId: String,
    val productType: String,
    val timestamp: String,
    val cmd: String,
    val params: Any
)

object CommandPayloadFactory {
    fun create(
        version: String,
        cmdId: String,
        deviceId: String,
        productType: String,
        timestamp: String,
        cmd: String,
        params: Any,
        gson: Gson = Gson()
    ): PreparedCommand {
        require(cmdId.isNotBlank()) { "cmdId must not be blank" }
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }
        require(productType.isNotBlank()) { "productType must not be blank" }
        require(cmd.isNotBlank()) { "cmd must not be blank" }
        val payload = gson.toJson(
            CommandPayload(version, cmdId, deviceId, productType, timestamp, cmd, params)
        )
        return PreparedCommand(cmdId = cmdId, cmd = cmd, payload = payload)
    }
}

data class CoverageStart(
    val blockId: Long,
    val cellRow: Int,
    val cellCol: Int,
    val innerRow: Int,
    val innerCol: Int,
    val heading: Int
)

data class CoverageCommandParams(
    val mapId: Long,
    val mapVersion: Long,
    val useCurrentPose: Boolean,
    val start: CoverageStart? = null,
    val targetBlockIds: List<Long>,
    val globalPlan: Boolean = true
)

data class CoverageTaskSelection(
    val useCurrentPose: Boolean,
    val start: CoverageStart?,
    val targetBlockIds: List<Long>,
    val globalPlan: Boolean
)

data class MissionState(
    val missionId: String? = null,
    val rootMissionId: String? = null,
    val taskKind: String? = null,
    val runState: String? = null,
    val orchestrationState: String? = null,
    val taskStackDepth: Int? = null,
    val interruptionReason: String? = null,
    val operationalMode: String? = null,
    val safetyState: String? = null,
    val phase: String? = null,
    val activeAction: String? = null,
    val waypointIndex: Int? = null,
    val waypointCount: Int? = null,
    val errorCode: Int? = null,
    val errorRetryable: Boolean? = null,
    val errorSource: String? = null,
    val errorMessage: String? = null
) {
    /** V4 目标任务命令始终操作用户根任务；旧 Robot 无该字段时回退当前任务。 */
    val controlMissionId: String?
        get() = rootMissionId?.takeIf { it.isNotBlank() }
            ?: missionId?.takeIf { it.isNotBlank() }

    /** 当前协议只开放 coverage，因此出现根任务 ID 时根任务类型可确定为 coverage。 */
    val rootTaskKind: String?
        get() = when {
            !rootMissionId.isNullOrBlank() -> "coverage"
            else -> taskKind
        }
}

data class RemoteMessage(
    val version: String,
    val deviceId: String,
    val productType: String,
    val timestamp: String,
    val linearSpeedCms: Double,
    val angularSpeedRadps: Double,
    val durationMs: Int
)

object RemoteControlContract {
    const val MIN_LINEAR_SPEED_CMS = -50.0
    const val MAX_LINEAR_SPEED_CMS = 50.0
    const val MIN_ANGULAR_SPEED_RADPS = -0.5
    const val MAX_ANGULAR_SPEED_RADPS = 0.5

    fun clampLinear(value: Double): Double =
        value.coerceIn(MIN_LINEAR_SPEED_CMS, MAX_LINEAR_SPEED_CMS)

    fun clampAngular(value: Double): Double =
        value.coerceIn(MIN_ANGULAR_SPEED_RADPS, MAX_ANGULAR_SPEED_RADPS)
}

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
    val timestampMillis: Long = System.currentTimeMillis(),
    val paramsSummary: String? = null
)

enum class CommandStatus {
    IDLE,
    SENDING,
    SUCCESS,
    FAILED,
    TIMEOUT,
    CONNECTION_LOST
}
