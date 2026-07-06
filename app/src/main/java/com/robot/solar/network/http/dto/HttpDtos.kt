package com.robot.solar.network.http.dto

import com.google.gson.annotations.SerializedName

data class LoginRequest(
    val email: String,
    val password: String
)

data class TokenResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("token_type") val tokenType: String = "bearer",
    @SerializedName("expires_in") val expiresIn: Int = 0
)

data class DeviceDto(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("display_name") val displayName: String
)

data class JobDto(
    val id: Int,
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("started_at") val startedAt: String,
    @SerializedName("finished_at") val finishedAt: String?,
    val status: String,
    @SerializedName("cleaned_rows") val cleanedRows: Int,
    val note: String?
)

data class FirmwareDto(
    val version: String,
    @SerializedName("download_url") val downloadUrl: String,
    @SerializedName("release_notes") val releaseNotes: String?,
    @SerializedName("published_at") val publishedAt: String
)

data class FirmwareUpgradeRequest(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("target_version") val targetVersion: String? = null
)

data class FirmwareUpgradeResponse(
    val status: String,
    @SerializedName("device_id") val deviceId: String,
    val message: String,
    @SerializedName("target_version") val targetVersion: String?
)

data class WifiConfigDto(
    @SerializedName("device_id") val deviceId: String,
    val ssid: String?,
    val configured: Boolean
)

data class WifiConfigUpdate(
    val ssid: String,
    val password: String
)
