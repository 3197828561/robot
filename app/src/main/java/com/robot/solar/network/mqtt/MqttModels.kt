package com.robot.solar.network.mqtt

import com.google.gson.annotations.SerializedName

/** cloud_comm telemetry 消息（vgsolar.cloud_comm.v1） */
data class TelemetryMessage(
    val schema: String?,
    val type: String?,
    @SerializedName("device_id") val deviceId: String?,
    @SerializedName("timestamp_ms") val timestampMs: Long?,
    val status: RoverStatus?,
    @SerializedName("nav_status") val navStatus: NavStatus?
)

data class RoverStatus(
    @SerializedName("battery_percent") val batteryPercent: Double?,
    @SerializedName("latitude_deg") val latitudeDeg: Double?,
    @SerializedName("longitude_deg") val longitudeDeg: Double?,
    @SerializedName("yaw_deg") val yawDeg: Double?,
    @SerializedName("linear_velocity_mps") val linearVelocityMps: Double?,
    @SerializedName("roll_deg") val rollDeg: Double?,
    @SerializedName("pitch_deg") val pitchDeg: Double?,
    @SerializedName("control_mode") val controlMode: Int?,
    @SerializedName("motion_state") val motionState: Int?,
    @SerializedName("fault_code") val faultCode: Int?,
    @SerializedName("gps_status") val gpsStatus: Int?,
    @SerializedName("fcu_connected") val fcuConnected: Boolean?,
    /** 联调扩展：设备温度 °C */
    @SerializedName("temperature_c") val temperatureC: Double?,
    /** 联调扩展：累计里程 m */
    @SerializedName("total_mileage_m") val totalMileageM: Double?,
    /** 联调扩展：已清洁行数 */
    @SerializedName("cleaned_rows") val cleanedRows: Int?,
    /** 联调扩展：大气气压 kPa */
    @SerializedName("pressure_kpa") val pressureKpa: Double?,
    /** 联调扩展：防摔传感器左/右距离 m */
    @SerializedName("anti_fall_left_m") val antiFallLeftM: Double?,
    @SerializedName("anti_fall_right_m") val antiFallRightM: Double?
)

data class NavStatus(
    @SerializedName("nav_state") val navState: Int?,
    @SerializedName("coord_mode") val coordMode: Int?,
    @SerializedName("distance_to_target_m") val distanceToTargetM: Double?,
    @SerializedName("heading_error_deg") val headingErrorDeg: Double?
)

data class ConnectionMessage(
    val schema: String?,
    val type: String?,
    @SerializedName("device_id") val deviceId: String?,
    val state: String?
)

data class CmdFeedbackEvent(
    val schema: String?,
    val type: String?,
    @SerializedName("received_cmd_type") val receivedCmdType: Int?,
    @SerializedName("exec_status") val execStatus: Int?
)
