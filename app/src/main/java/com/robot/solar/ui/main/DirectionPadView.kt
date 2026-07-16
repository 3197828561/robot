package com.robot.solar.ui.main

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.robot.solar.R

class DirectionPadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var listener: Listener? = null
    var controlsEnabled: Boolean = false
        set(value) {
            field = value
            if (!value) cancelInput()
            alpha = if (value) 1f else 0.55f
            invalidate()
        }

    private val normalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.manual_direction_fill) }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.control_primary) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.control_on_dark)
        textAlign = Paint.Align.CENTER
        textSize = 18f * resources.displayMetrics.density
        isFakeBoldText = true
    }
    private val pressedPointers = mutableMapOf<Int, ManualDirection>()
    private var activeDirection: ManualDirection? = null
    private var conflictReported = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height) / 3f
        drawButton(canvas, ManualDirection.FORWARD, width / 2f, size / 2f, size, "▲")
        drawButton(canvas, ManualDirection.LEFT, width / 2f - size, size * 1.5f, size, "◀")
        drawButton(canvas, ManualDirection.RIGHT, width / 2f + size, size * 1.5f, size, "▶")
        drawButton(canvas, ManualDirection.BACKWARD, width / 2f, size * 2.5f, size, "▼")
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!controlsEnabled) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                directionAt(event.getX(index), event.getY(index))?.let {
                    pressedPointers[event.getPointerId(index)] = it
                }
                evaluateInput()
            }
            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    val id = event.getPointerId(index)
                    val direction = directionAt(event.getX(index), event.getY(index))
                    if (direction == null) pressedPointers.remove(id) else pressedPointers[id] = direction
                }
                evaluateInput()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                pressedPointers.remove(event.getPointerId(event.actionIndex))
                evaluateInput()
            }
            MotionEvent.ACTION_CANCEL -> cancelInput()
        }
        parent?.requestDisallowInterceptTouchEvent(pressedPointers.isNotEmpty())
        invalidate()
        return true
    }

    fun cancelInput() {
        val hadInput = activeDirection != null || pressedPointers.isNotEmpty()
        pressedPointers.clear()
        activeDirection = null
        conflictReported = false
        if (hadInput) listener?.onRelease()
        invalidate()
    }

    private fun evaluateInput() {
        val directions = pressedPointers.values.toSet()
        when {
            directions.size > 1 -> {
                if (!conflictReported) listener?.onConflict()
                conflictReported = true
                activeDirection = null
            }
            directions.size == 1 -> {
                conflictReported = false
                val direction = directions.first()
                if (activeDirection != direction) {
                    if (activeDirection != null) listener?.onRelease()
                    activeDirection = direction
                    listener?.onPress(direction)
                }
            }
            else -> {
                conflictReported = false
                if (activeDirection != null) listener?.onRelease()
                activeDirection = null
            }
        }
    }

    private fun drawButton(canvas: Canvas, direction: ManualDirection, cx: Float, cy: Float, size: Float, label: String) {
        val gap = 6f * resources.displayMetrics.density
        val rect = RectF(cx - size / 2f + gap, cy - size / 2f + gap, cx + size / 2f - gap, cy + size / 2f - gap)
        val selected = activeDirection == direction || direction in pressedPointers.values
        canvas.drawRoundRect(rect, 18f, 18f, if (selected) pressedPaint else normalPaint)
        canvas.drawText(label, cx, cy - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
    }

    private fun directionAt(x: Float, y: Float): ManualDirection? {
        val column = (x / (width / 3f)).toInt().coerceIn(0, 2)
        val row = (y / (height / 3f)).toInt().coerceIn(0, 2)
        return when (row to column) {
            0 to 1 -> ManualDirection.FORWARD
            1 to 0 -> ManualDirection.LEFT
            1 to 2 -> ManualDirection.RIGHT
            2 to 1 -> ManualDirection.BACKWARD
            else -> null
        }
    }

    interface Listener {
        fun onPress(direction: ManualDirection)
        fun onRelease()
        fun onConflict()
    }
}

enum class ManualDirection(val linearSpeedCms: Double, val angularSpeedRadps: Double) {
    FORWARD(20.0, 0.0),
    BACKWARD(-20.0, 0.0),
    LEFT(0.0, -0.5),
    RIGHT(0.0, 0.5)
}
