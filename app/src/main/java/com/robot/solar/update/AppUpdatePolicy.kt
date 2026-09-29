package com.robot.solar.update

import com.robot.solar.network.http.dto.AppReleaseDto

data class UpdateDecision(
    val available: Boolean,
    val mandatory: Boolean
)

object AppUpdatePolicy {
    fun decide(currentVersionCode: Long, release: AppReleaseDto): UpdateDecision =
        UpdateDecision(
            available = release.versionCode > currentVersionCode,
            mandatory = release.versionCode > currentVersionCode &&
                (release.mandatory || currentVersionCode < release.minSupportedVersionCode)
        )
}
