package com.robot.solar.ui.common

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

fun View.applySystemBarPadding() {
    val initialLeft = paddingLeft
    val initialTop = paddingTop
    val initialRight = paddingRight
    val initialBottom = paddingBottom

    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.setPadding(
            initialLeft + bars.left,
            initialTop + bars.top,
            initialRight + bars.right,
            initialBottom + bars.bottom
        )
        insets
    }
    ViewCompat.requestApplyInsets(this)
}
