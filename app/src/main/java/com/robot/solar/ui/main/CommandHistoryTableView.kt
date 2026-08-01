package com.robot.solar.ui.main

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.robot.solar.R

data class CommandHistoryDisplayRow(
    val time: String,
    val command: String,
    val params: String,
    val status: String,
    val description: String
)

class CommandHistoryTableView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val dataRows = mutableListOf<List<TextView>>()
    private val columnWeights = floatArrayOf(1.0f, 1.2f, 1.8f, 1.1f, 2.4f)

    init {
        orientation = VERTICAL
        minimumWidth = dp(720)
        background = roundedBackground(ContextCompat.getColor(context, R.color.control_stroke))
        setPadding(dp(1), dp(1), dp(1), dp(1))
        addTableRow(
            values = listOf("时间", "命令", "参数", "状态", "说明"),
            header = true
        )
        repeat(4) {
            addDivider()
            dataRows += addTableRow(List(5) { "--" }, header = false)
        }
    }

    fun submitRows(rows: List<CommandHistoryDisplayRow>) {
        dataRows.forEachIndexed { index, cells ->
            val row = rows.getOrNull(index)
            val values = row?.let {
                listOf(it.time, it.command, it.params, it.status, it.description)
            } ?: List(5) { "--" }
            cells.forEachIndexed { cellIndex, textView ->
                textView.text = values[cellIndex]
                textView.contentDescription =
                    "${COLUMN_NAMES[cellIndex]}：${values[cellIndex]}"
            }
        }
    }

    private fun addTableRow(values: List<String>, header: Boolean): List<TextView> {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(if (header) 40 else 46)
            setPadding(dp(4), 0, dp(4), 0)
            setBackgroundColor(
                ContextCompat.getColor(
                    context,
                    if (header) R.color.control_page_bg else R.color.control_panel
                )
            )
        }
        val cells = values.mapIndexed { index, value ->
            TextView(context).apply {
                layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, columnWeights[index])
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(5), dp(8), dp(5))
                text = value
                setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (header) R.color.control_text_muted else R.color.control_text
                    )
                )
                textSize = if (header) 12f else 12f
                if (header) setTypeface(typeface, Typeface.BOLD)
                maxLines = if (index == 4 && !header) 2 else 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
        }
        cells.forEach(row::addView)
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        return cells
    }

    private fun addDivider() {
        addView(
            View(context).apply {
                setBackgroundColor(ContextCompat.getColor(context, R.color.control_stroke))
            },
            LayoutParams(LayoutParams.MATCH_PARENT, dp(1))
        )
    }

    private fun roundedBackground(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(8).toFloat()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private val COLUMN_NAMES = listOf("时间", "命令", "参数", "状态", "说明")
    }
}
