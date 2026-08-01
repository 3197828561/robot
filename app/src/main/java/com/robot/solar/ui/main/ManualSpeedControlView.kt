package com.robot.solar.ui.main

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.slider.Slider
import com.robot.solar.R
import com.robot.solar.viewmodel.ManualSpeedPolicy
import com.robot.solar.viewmodel.ManualSpeedPreset
import com.robot.solar.viewmodel.ManualSpeedSettings
import java.util.Locale

class ManualSpeedControlView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    var onSettingsChanged: ((ManualSpeedSettings) -> Unit)? = null
    private var settings = ManualSpeedSettings()
    private var rendering = false

    private val presetGroup: MaterialButtonToggleGroup
    private val linearSlider: Slider
    private val angularSlider: Slider
    private val linearValue: TextView
    private val angularValue: TextView
    private val adjustmentButtons: List<MaterialButton>

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_manual_speed_control, this, true)
        presetGroup = findViewById(R.id.speedPresetGroup)
        linearSlider = findViewById(R.id.sliderLinearSpeed)
        angularSlider = findViewById(R.id.sliderAngularSpeed)
        linearValue = findViewById(R.id.tvLinearSpeedValue)
        angularValue = findViewById(R.id.tvAngularSpeedValue)

        presetGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!rendering && isChecked) {
                presetForButton(checkedId)?.let {
                    updateAndNotify(ManualSpeedPolicy.fromPreset(it))
                }
            }
        }
        linearSlider.addOnChangeListener { _, value, fromUser ->
            if (!rendering && fromUser) {
                updateAndNotify(settings.copy(linearSpeedCms = value.toDouble()))
            }
        }
        angularSlider.addOnChangeListener { _, value, fromUser ->
            if (!rendering && fromUser) {
                updateAndNotify(settings.copy(angularSpeedRadps = value.toDouble()))
            }
        }
        val linearMinus = findViewById<MaterialButton>(R.id.btnLinearMinus)
        val linearPlus = findViewById<MaterialButton>(R.id.btnLinearPlus)
        val angularMinus = findViewById<MaterialButton>(R.id.btnAngularMinus)
        val angularPlus = findViewById<MaterialButton>(R.id.btnAngularPlus)
        adjustmentButtons = listOf(linearMinus, linearPlus, angularMinus, angularPlus)
        linearMinus.setOnClickListener {
            updateAndNotify(
                settings.copy(
                    linearSpeedCms = settings.linearSpeedCms - ManualSpeedPolicy.LINEAR_STEP_CMS
                )
            )
        }
        linearPlus.setOnClickListener {
            updateAndNotify(
                settings.copy(
                    linearSpeedCms = settings.linearSpeedCms + ManualSpeedPolicy.LINEAR_STEP_CMS
                )
            )
        }
        angularMinus.setOnClickListener {
            updateAndNotify(
                settings.copy(
                    angularSpeedRadps =
                        settings.angularSpeedRadps - ManualSpeedPolicy.ANGULAR_STEP_RADPS
                )
            )
        }
        angularPlus.setOnClickListener {
            updateAndNotify(
                settings.copy(
                    angularSpeedRadps =
                        settings.angularSpeedRadps + ManualSpeedPolicy.ANGULAR_STEP_RADPS
                )
            )
        }
        render(settings)
    }

    fun setSettings(value: ManualSpeedSettings) {
        val normalized = ManualSpeedPolicy.normalize(value)
        if (normalized != settings) render(normalized)
    }

    fun setControlsEnabled(enabled: Boolean) {
        linearSlider.isEnabled = enabled
        angularSlider.isEnabled = enabled
        adjustmentButtons.forEach { it.isEnabled = enabled }
        for (index in 0 until presetGroup.childCount) {
            presetGroup.getChildAt(index).isEnabled = enabled
        }
        alpha = if (enabled) 1f else 0.55f
    }

    private fun updateAndNotify(value: ManualSpeedSettings) {
        val normalized = ManualSpeedPolicy.normalize(value)
        render(normalized)
        onSettingsChanged?.invoke(normalized)
    }

    private fun render(value: ManualSpeedSettings) {
        settings = ManualSpeedPolicy.normalize(value)
        rendering = true
        linearSlider.value = settings.linearSpeedCms.toFloat()
        angularSlider.value = settings.angularSpeedRadps.toFloat()
        linearValue.text = "${settings.linearSpeedCms.toInt()}\ncm/s"
        angularValue.text = String.format(
            Locale.US,
            "%.1f\nrad/s",
            settings.angularSpeedRadps
        )
        presetGroup.clearChecked()
        ManualSpeedPolicy.presetFor(settings)?.let { preset ->
            presetGroup.check(buttonForPreset(preset))
        }
        rendering = false
    }

    private fun presetForButton(buttonId: Int): ManualSpeedPreset? = when (buttonId) {
        R.id.btnSpeedSlow -> ManualSpeedPreset.SLOW
        R.id.btnSpeedStandard -> ManualSpeedPreset.STANDARD
        R.id.btnSpeedHigh -> ManualSpeedPreset.HIGH
        else -> null
    }

    private fun buttonForPreset(preset: ManualSpeedPreset): Int = when (preset) {
        ManualSpeedPreset.SLOW -> R.id.btnSpeedSlow
        ManualSpeedPreset.STANDARD -> R.id.btnSpeedStandard
        ManualSpeedPreset.HIGH -> R.id.btnSpeedHigh
    }
}
