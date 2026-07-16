package com.robot.solar.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.robot.solar.R

class BatteryIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.control_on_dark_muted)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.control_on_dark)
        textSize = 12f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.LEFT
    }
    private var percent: Int? = null

    fun setBatteryPercent(value: Int?) {
        percent = value?.coerceIn(0, 100)
        contentDescription = value?.let { context.getString(R.string.battery_content_description, it) }
            ?: context.getString(R.string.battery_unknown)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize((88 * density).toInt(), widthMeasureSpec), resolveSize((30 * density).toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val battery = RectF(1f * density, 7f * density, 35f * density, 23f * density)
        canvas.drawRoundRect(battery, 3f * density, 3f * density, outline)
        canvas.drawRoundRect(RectF(36f * density, 11f * density, 39f * density, 19f * density), density, density, outline)
        val value = percent
        if (value != null) {
            fill.color = when {
                value <= 15 -> context.getColor(R.color.control_danger)
                value <= 35 -> context.getColor(R.color.control_warning)
                else -> context.getColor(R.color.control_success)
            }
            val innerWidth = 30f * density * value / 100f
            canvas.drawRoundRect(RectF(3f * density, 9f * density, 3f * density + innerWidth, 21f * density), 2f * density, 2f * density, fill)
        }
        canvas.drawText(value?.let { "$it%" } ?: "--", 45f * density, 20f * density, text)
    }
}
