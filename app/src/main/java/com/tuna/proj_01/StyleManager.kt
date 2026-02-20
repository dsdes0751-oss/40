package com.tuna.proj_01

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint

object StyleManager {

    val textPaint: TextPaint by lazy {
        TextPaint().apply {
            color = Color.BLACK
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            isAntiAlias = true
        }
    }

    val maskingPaint: Paint by lazy {
        Paint().apply {
            style = Paint.Style.FILL
            color = Color.WHITE
            isAntiAlias = true
        }
    }

    val bubbleBackgroundPaint: Paint by lazy {
        Paint().apply {
            style = Paint.Style.FILL
            color = Color.WHITE
            isAntiAlias = true
        }
    }
}
