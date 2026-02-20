package com.tuna.proj_01

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.min

object OcrPreprocessor {

    enum class Mode {
        NORMAL,
        STRONG
    }

    fun preprocessForOcr(src: Bitmap, mode: Mode): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.colorFilter = buildFilter(mode)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    private fun buildFilter(mode: Mode): ColorMatrixColorFilter {
        val matrix = ColorMatrix()
        matrix.setSaturation(0f)

        val contrast = if (mode == Mode.STRONG) 1.55f else 1.25f
        val brightness = if (mode == Mode.STRONG) -10f else -2f
        val t = (-0.5f * contrast + 0.5f) * 255f + brightness

        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, t,
                0f, contrast, 0f, 0f, t,
                0f, 0f, contrast, 0f, t,
                0f, 0f, 0f, 1f, 0f
            )
        )
        matrix.postConcat(contrastMatrix)
        return ColorMatrixColorFilter(matrix)
    }

    fun scoreTextQuality(text: String): Float {
        val compact = text.filterNot { it.isWhitespace() }
        if (compact.isEmpty()) return 0f

        val total = compact.length.toFloat()
        val valid = compact.count { it.isLetterOrDigit() }.toFloat()
        val suspicious = compact.count { it in setOf('?', '|', '_', '~', '`', '^', '*') }.toFloat()
        val validRatio = valid / total
        val suspiciousRatio = suspicious / total
        val lengthBonus = min(1f, total / 24f)
        val repeatPenalty = repeatedCharPenalty(compact)

        val score = validRatio * 0.65f + lengthBonus * 0.20f + (1f - suspiciousRatio) * 0.15f - repeatPenalty
        return score.coerceIn(0f, 1f)
    }

    private fun repeatedCharPenalty(text: String): Float {
        var maxRun = 1
        var run = 1
        for (i in 1 until text.length) {
            run = if (text[i] == text[i - 1]) run + 1 else 1
            maxRun = max(maxRun, run)
        }
        return when {
            maxRun >= 8 -> 0.25f
            maxRun >= 6 -> 0.15f
            maxRun >= 4 -> 0.07f
            else -> 0f
        }
    }
}
