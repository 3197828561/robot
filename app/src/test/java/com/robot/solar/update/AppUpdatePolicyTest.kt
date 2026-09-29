package com.robot.solar.update

import com.robot.solar.network.http.dto.AppReleaseDto
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatePolicyTest {
    @Test
    fun newerOptionalVersionIsAvailable() {
        val decision = AppUpdatePolicy.decide(3, release(versionCode = 4))
        assertTrue(decision.available)
        assertFalse(decision.mandatory)
    }

    @Test
    fun minimumSupportedVersionForcesUpdate() {
        val decision = AppUpdatePolicy.decide(3, release(versionCode = 5, minimum = 4))
        assertTrue(decision.mandatory)
    }

    @Test
    fun sameVersionDoesNotUpdate() {
        val decision = AppUpdatePolicy.decide(3, release(versionCode = 3, mandatory = true))
        assertFalse(decision.available)
        assertFalse(decision.mandatory)
    }

    private fun release(
        versionCode: Long,
        minimum: Long = 1,
        mandatory: Boolean = false
    ) = AppReleaseDto(
        channel = "test",
        versionCode = versionCode,
        versionName = "1.0.$versionCode",
        minSupportedVersionCode = minimum,
        mandatory = mandatory,
        sha256 = "0".repeat(64),
        fileSizeBytes = 1,
        releaseNotes = null,
        gitCommit = null,
        publishedAt = "2026-09-29T00:00:00Z",
        contentUrl = "/content"
    )
}
