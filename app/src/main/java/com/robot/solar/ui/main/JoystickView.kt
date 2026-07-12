package com.robot.solar.ui.main

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.robot.solar.R
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class JoystickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var listener: Listener? = null
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.joystick_chassis_fill) }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.joystick_chassis_stroke)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.joystick_stop_fill) }
    private var knobX = 0f
    private var knobY = 0f
    private var radius = 1f
    private val deadZone = 0.12f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        radius = min(w, h) * 0.42f
        resetKnob()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, radius, basePaint)
        canvas.drawCircle(cx, cy, radius, ringPaint)
        canvas.drawCircle(cx, cy, radius * deadZone, ringPaint)
        canvas.drawCircle(knobX, knobY, radius * 0.24f, knobPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                updateKnob(event.x, event.y)
                listener?.onStart(normalizedX(), normalizedY())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateKnob(event.x, event.y)
                listener?.onMove(normalizedX(), normalizedY())
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                resetKnob()
                parent?.requestDisallowInterceptTouchEvent(false)
                listener?.onStop()
                return true
            }
        }
        return true
    }

    fun resetKnob() {
        knobX = width / 2f
        knobY = height / 2f
        invalidate()
    }

    private fun updateKnob(x: Float, y: Float) {
        val cx = width / 2f
        val cy = height / 2f
        val dx = x - cx
        val dy = y - cy
        val distance = max(1f, hypot(dx, dy))
        val scale = min(radius, distance) / distance
        knobX = cx + dx * scale
        knobY = cy + dy * scale
        invalidate()
    }

    private fun normalizedX(): Double {
        val value = ((knobX - width / 2f) / radius).coerceIn(-1f, 1f)
        return if (kotlin.math.abs(value) < deadZone) 0.0 else value.toDouble()
    }

    private fun normalizedY(): Double {
        val value = (-(knobY - height / 2f) / radius).coerceIn(-1f, 1f)
        return if (kotlin.math.abs(value) < deadZone) 0.0 else value.toDouble()
    }

    interface Listener {
        fun onStart(x: Double, y: Double)
        fun onMove(x: Double, y: Double)
        fun onStop()
    }
}
