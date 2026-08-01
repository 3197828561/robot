package com.robot.solar.viewmodel

import com.robot.solar.network.mqtt.RemoteControlContract
import com.robot.solar.ui.main.ManualDirection
import kotlin.math.roundToInt

data class ManualSpeedSettings(
    val linearSpeedCms: Double = DEFAULT_LINEAR_SPEED_CMS,
    val angularSpeedRadps: Double = DEFAULT_ANGULAR_SPEED_RADPS
) {
    companion object {
        const val DEFAULT_LINEAR_SPEED_CMS = 30.0
        const val DEFAULT_ANGULAR_SPEED_RADPS = 0.3
    }
}

enum class ManualSpeedPreset(
    val linearSpeedCms: Double,
    val angularSpeedRadps: Double
) {
    SLOW(10.0, 0.1),
    STANDARD(30.0, 0.3),
    HIGH(50.0, 0.5)
}

data class RemoteVelocity(
    val linearSpeedCms: Double,
    val angularSpeedRadps: Double
)

object ManualSpeedPolicy {
    const val LINEAR_STEP_CMS = 1.0
    const val ANGULAR_STEP_RADPS = 0.1
    const val UI_MIN_LINEAR_SPEED_CMS = 0.0
    const val UI_MAX_LINEAR_SPEED_CMS = RemoteControlContract.MAX_LINEAR_SPEED_CMS
    const val UI_MIN_ANGULAR_SPEED_RADPS = 0.0
    const val UI_MAX_ANGULAR_SPEED_RADPS = RemoteControlContract.MAX_ANGULAR_SPEED_RADPS

    fun normalize(settings: ManualSpeedSettings): ManualSpeedSettings =
        ManualSpeedSettings(
            linearSpeedCms = normalizeLinear(settings.linearSpeedCms),
            angularSpeedRadps = normalizeAngular(settings.angularSpeedRadps)
        )

    fun normalizeLinear(value: Double): Double {
        if (!value.isFinite()) return ManualSpeedSettings.DEFAULT_LINEAR_SPEED_CMS
        return value
            .coerceIn(UI_MIN_LINEAR_SPEED_CMS, UI_MAX_LINEAR_SPEED_CMS)
            .roundToInt()
            .toDouble()
    }

    fun normalizeAngular(value: Double): Double {
        if (!value.isFinite()) return ManualSpeedSettings.DEFAULT_ANGULAR_SPEED_RADPS
        return (
            value.coerceIn(UI_MIN_ANGULAR_SPEED_RADPS, UI_MAX_ANGULAR_SPEED_RADPS) * 10.0
            ).roundToInt() / 10.0
    }

    fun fromPreset(preset: ManualSpeedPreset): ManualSpeedSettings =
        ManualSpeedSettings(preset.linearSpeedCms, preset.angularSpeedRadps)

    fun presetFor(settings: ManualSpeedSettings): ManualSpeedPreset? {
        val normalized = normalize(settings)
        return ManualSpeedPreset.entries.firstOrNull {
            it.linearSpeedCms == normalized.linearSpeedCms &&
                it.angularSpeedRadps == normalized.angularSpeedRadps
        }
    }

    fun velocityFor(
        direction: ManualDirection,
        settings: ManualSpeedSettings
    ): RemoteVelocity {
        val normalized = normalize(settings)
        return RemoteVelocity(
            linearSpeedCms = RemoteControlContract.clampLinear(
                normalized.linearSpeedCms * direction.linearSign
            ),
            angularSpeedRadps = RemoteControlContract.clampAngular(
                normalized.angularSpeedRadps * direction.angularSign
            )
        )
    }
}
