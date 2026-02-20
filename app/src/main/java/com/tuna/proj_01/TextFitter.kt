package com.tuna.proj_01

import android.content.res.Resources
import android.graphics.Rect
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.max
import kotlin.math.min

object TextFitter {

    data class FitResult(
        val text: String,
        val fontPx: Float,
        val layout: StaticLayout,
        val textRect: Rect
    )

    fun fitText(
        text: String,
        bubbleRect: Rect,
        paint: TextPaint,
        isVertical: Boolean,
        paddingPx: Int,
        maxLines: Int? = null
    ): FitResult? {
        val rawText = text.trim()
        if (rawText.isEmpty()) return null

        val usableRect = Rect(bubbleRect)
        usableRect.inset(paddingPx, paddingPx)
        if (usableRect.width() < 20 || usableRect.height() < 20) return null

        val minFontPx = dpToPx(14f)
        val maxFontByBubble = min(usableRect.width(), usableRect.height()) * 0.22f
        val maxFontPx = min(dpToPx(48f), maxFontByBubble).coerceAtLeast(minFontPx)

        val probePaint = TextPaint(paint).apply { isAntiAlias = true }
        var low = minFontPx
        var high = maxFontPx
        var bestText = rawText
        var bestFont = minFontPx
        var hasBest = false

        repeat(8) {
            val mid = (low + high) / 2f
            probePaint.textSize = mid
            val shapedText = shapeForBalance(rawText, probePaint, usableRect.width(), isVertical)
            val layout = createLayout(shapedText, probePaint, usableRect.width(), maxLines)
            val lineMax = getLineMaxWidth(layout)
            val fits = layout.height <= usableRect.height() &&
                lineMax <= usableRect.width() + 0.5f &&
                (maxLines == null || layout.lineCount <= maxLines)

            if (fits) {
                bestText = shapedText
                bestFont = mid
                hasBest = true
                low = mid
            } else {
                high = mid
            }
        }

        if (!hasBest) return null

        paint.textSize = bestFont
        val finalLayout = createLayout(bestText, paint, usableRect.width(), maxLines)
        val left = (usableRect.centerX() - finalLayout.width / 2f).toInt()
            .coerceIn(usableRect.left, usableRect.right - finalLayout.width)
        val top = (usableRect.centerY() - finalLayout.height / 2f).toInt()
            .coerceIn(usableRect.top, usableRect.bottom - finalLayout.height)

        return FitResult(
            text = bestText,
            fontPx = bestFont,
            layout = finalLayout,
            textRect = Rect(left, top, left + finalLayout.width, top + finalLayout.height)
        )
    }

    fun createLayout(
        text: String,
        paint: TextPaint,
        width: Int,
        maxLines: Int? = null
    ): StaticLayout {
        val safeWidth = width.coerceAtLeast(1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, safeWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .setLineSpacing(0f, 1f)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            if (maxLines != null) {
                builder.setMaxLines(maxLines)
            }
            return builder.build()
        }

        @Suppress("DEPRECATION")
        return StaticLayout(text, paint, safeWidth, Layout.Alignment.ALIGN_CENTER, 1f, 0f, false)
    }

    fun getLineMaxWidth(layout: StaticLayout): Float {
        var maxWidth = 0f
        for (i in 0 until layout.lineCount) {
            maxWidth = max(maxWidth, layout.getLineWidth(i))
        }
        return maxWidth
    }

    private fun shapeForBalance(text: String, paint: TextPaint, width: Int, isVertical: Boolean): String {
        if (isVertical) return text
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size < 3) return text

        val lines = greedyWrap(words, paint, width)
        if (lines.size < 2) return text

        var changed = false
        var guard = 0
        while (guard < words.size) {
            guard++
            val widths = lines.map { lineWidth(it, paint) }
            val avg = widths.average().toFloat()
            val lastWidth = widths.last()
            if (lastWidth >= avg * 0.55f) break

            val prev = lines[lines.lastIndex - 1]
            val last = lines.last()
            if (prev.size <= 1) break

            val moved = prev.removeAt(prev.lastIndex)
            last.add(0, moved)
            if (lineWidth(last, paint) > width) {
                last.removeAt(0)
                prev.add(moved)
                break
            }
            changed = true
        }

        return if (changed) {
            lines.joinToString("\n") { it.joinToString(" ") }
        } else {
            text
        }
    }

    private fun greedyWrap(words: List<String>, paint: TextPaint, width: Int): MutableList<MutableList<String>> {
        val out = mutableListOf<MutableList<String>>()
        var current = mutableListOf<String>()

        for (word in words) {
            val candidate = if (current.isEmpty()) word else current.joinToString(" ") + " " + word
            if (current.isEmpty() || paint.measureText(candidate) <= width) {
                current.add(word)
            } else {
                out.add(current)
                current = mutableListOf(word)
            }
        }
        if (current.isNotEmpty()) out.add(current)
        return out
    }

    private fun lineWidth(words: List<String>, paint: TextPaint): Float {
        if (words.isEmpty()) return 0f
        return paint.measureText(words.joinToString(" "))
    }

    private fun dpToPx(dp: Float): Float {
        return dp * Resources.getSystem().displayMetrics.density
    }
}
