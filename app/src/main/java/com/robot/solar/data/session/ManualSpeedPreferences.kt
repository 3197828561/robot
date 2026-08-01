package com.robot.solar.data.session

import android.content.Context
import androidx.core.content.edit
import com.robot.solar.viewmodel.ManualSpeedPolicy
import com.robot.solar.viewmodel.ManualSpeedSettings

class ManualSpeedPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun load(deviceId: String): ManualSpeedSettings {
        val defaults = ManualSpeedSettings()
        return ManualSpeedPolicy.normalize(
            ManualSpeedSettings(
                linearSpeedCms = prefs.getFloat(
                    key(deviceId, KEY_LINEAR),
                    defaults.linearSpeedCms.toFloat()
                ).toDouble(),
                angularSpeedRadps = prefs.getFloat(
                    key(deviceId, KEY_ANGULAR),
                    defaults.angularSpeedRadps.toFloat()
                ).toDouble()
            )
        )
    }

    fun save(deviceId: String, settings: ManualSpeedSettings) {
        val normalized = ManualSpeedPolicy.normalize(settings)
        prefs.edit {
            putFloat(key(deviceId, KEY_LINEAR), normalized.linearSpeedCms.toFloat())
            putFloat(key(deviceId, KEY_ANGULAR), normalized.angularSpeedRadps.toFloat())
        }
    }

    private fun key(deviceId: String, suffix: String): String =
        "${deviceId.ifBlank { DEFAULT_DEVICE_KEY }}.$suffix"

    companion object {
        private const val PREFS_NAME = "manual_speed_settings"
        private const val DEFAULT_DEVICE_KEY = "default"
        private const val KEY_LINEAR = "linear_speed_cms"
        private const val KEY_ANGULAR = "angular_speed_radps"
    }
}
