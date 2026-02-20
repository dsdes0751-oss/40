package com.tuna.proj_01

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object BubbleDetector {

    data class BubbleStats(
        val avgLuma: Float,
        val avgColor: Int,
        val isDarkBubble: Boolean
    )

    private const val EDGE_TOL = 0.25f
    private const val COLOR_TOL = 70f

    fun estimateBubbleRect(original: Bitmap, seedRect: Rect): Rect {
        if (original.width <= 1 || original.height <= 1) return Rect(seedRect)

        val seed = clampRect(seedRect, original.width, original.height)
        if (seed.width() < 2 || seed.height() < 2) return fallbackRect(seed, original.width, original.height)

        val searchRect = buildSearchRect(seed, original.width, original.height)
        if (searchRect.width() < 2 || searchRect.height() < 2) {
            return fallbackRect(seed, original.width, original.height)
        }

        var patch: Bitmap? = null
        var small: Bitmap? = null

        return try {
            patch = Bitmap.createBitmap(
                original,
                searchRect.left,
                searchRect.top,
                searchRect.width(),
                searchRect.height()
            )

            val downscale = chooseDownscale(seed)
            val smallW = (patch.width / downscale).coerceAtLeast(1)
            val smallH = (patch.height / downscale).coerceAtLeast(1)
            small = Bitmap.createScaledBitmap(patch, smallW, smallH, true)

            val pixels = IntArray(smallW * smallH)
            small.getPixels(pixels, 0, smallW, 0, 0, smallW, smallH)
            val lumas = FloatArray(pixels.size) { idx -> luminance(pixels[idx]) }

            val seedInPatch = Rect(seed).apply { offset(-searchRect.left, -searchRect.top) }
            val seedSmall = scaleRectDown(seedInPatch, patch.width, patch.height, smallW, smallH)
            val seedAreaSmall = (seedSmall.width().coerceAtLeast(1) * seedSmall.height().coerceAtLeast(1)).coerceAtLeast(1)

            val seedPoint = chooseSeedPoint(seedSmall, lumas, smallW, smallH)
            val seedIndex = seedPoint.second * smallW + seedPoint.first
            val seedColor = pixels[seedIndex]
            val seedLuma = lumas[seedIndex]
            val lumaTol = if (seedLuma > 0.6f) 0.22f else 0.18f
            val colorTolSq = COLOR_TOL * COLOR_TOL
            val maxAreaSmall = min((smallW * smallH * 0.20f).toInt(), seedAreaSmall * 25).coerceAtLeast(seedAreaSmall)

            val visited = BooleanArray(pixels.size)
            val queue = IntArray(pixels.size)
            var head = 0
            var tail = 0
            var area = 0

            var minX = seedPoint.first
            var maxX = seedPoint.first
            var minY = seedPoint.second
            var maxY = seedPoint.second

            if (!matches(seedColor, seedLuma, seedColor, seedLuma, lumaTol, colorTolSq)) {
                return fallbackRect(seed, original.width, original.height)
            }

            visited[seedIndex] = true
            queue[tail++] = seedIndex
            area++

            val dirs = intArrayOf(-1, 0, 1, 0, -1)
            while (head < tail && area < maxAreaSmall) {
                val idx = queue[head++]
                val x = idx % smallW
                val y = idx / smallW
                val curLuma = lumas[idx]

                for (d in 0 until 4) {
                    val nx = x + dirs[d]
                    val ny = y + dirs[d + 1]
                    if (nx !in 0 until smallW || ny !in 0 until smallH) continue

                    val nIdx = ny * smallW + nx
                    if (visited[nIdx]) continue

                    val nLuma = lumas[nIdx]
                    if (!matches(pixels[nIdx], nLuma, seedColor, seedLuma, lumaTol, colorTolSq)) continue
                    if (abs(curLuma - nLuma) > EDGE_TOL) continue
                    if (area > seedAreaSmall && hasStrongEdge(lumas, nx, ny, smallW, smallH, EDGE_TOL)) continue

                    visited[nIdx] = true
                    queue[tail++] = nIdx
                    area++

                    if (nx < minX) minX = nx
                    if (nx > maxX) maxX = nx
                    if (ny < minY) minY = ny
                    if (ny > maxY) maxY = ny

                    if (area >= maxAreaSmall) break
                }
            }

            if (area < (seedAreaSmall * 1.2f).toInt() || area > (seedAreaSmall * 30f).toInt()) {
                return fallbackRect(seed, original.width, original.height)
            }

            val rectInPatch = Rect(
                minX * patch.width / smallW,
                minY * patch.height / smallH,
                ((maxX + 1) * patch.width + smallW - 1) / smallW,
                ((maxY + 1) * patch.height + smallH - 1) / smallH
            )

            val result = Rect(rectInPatch).apply { offset(searchRect.left, searchRect.top) }
            val clamped = clampRect(result, original.width, original.height)
            val seedArea = (seed.width() * seed.height()).coerceAtLeast(1)
            val resultArea = (clamped.width() * clamped.height()).coerceAtLeast(1)
            if (resultArea < (seedArea * 1.2f).toInt() || resultArea > (seedArea * 30f).toInt()) {
                fallbackRect(seed, original.width, original.height)
            } else {
                clamped
            }
        } catch (_: Exception) {
            fallbackRect(seed, original.width, original.height)
        } finally {
            if (patch != null && !patch.isRecycled) patch.recycle()
            if (small != null && !small.isRecycled) small.recycle()
        }
    }

    fun estimateBubbleStats(original: Bitmap, bubbleRect: Rect): BubbleStats {
        if (original.width <= 1 || original.height <= 1) {
            return BubbleStats(avgLuma = 1f, avgColor = Color.WHITE, isDarkBubble = false)
        }

        val rect = clampRect(bubbleRect, original.width, original.height)
        if (rect.width() < 1 || rect.height() < 1) {
            return BubbleStats(avgLuma = 1f, avgColor = Color.WHITE, isDarkBubble = false)
        }

        val step = (min(rect.width(), rect.height()) / 16).coerceIn(6, 10)
        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var lumaSum = 0f
        var count = 0

        var y = rect.top
        while (y < rect.bottom) {
            var x = rect.left
            while (x < rect.right) {
                val color = original.getPixel(x, y)
                rSum += Color.red(color)
                gSum += Color.green(color)
                bSum += Color.blue(color)
                lumaSum += luminance(color)
                count++
                x += step
            }
            y += step
        }

        if (count == 0) return BubbleStats(avgLuma = 1f, avgColor = Color.WHITE, isDarkBubble = false)

        val avgColor = Color.rgb((rSum / count).toInt(), (gSum / count).toInt(), (bSum / count).toInt())
        val avgLuma = (lumaSum / count).coerceIn(0f, 1f)
        return BubbleStats(avgLuma = avgLuma, avgColor = avgColor, isDarkBubble = avgLuma < 0.45f)
    }

    private fun buildSearchRect(seed: Rect, maxW: Int, maxH: Int): Rect {
        val expandX = (seed.width() * 1.8f).toInt().coerceAtLeast(20)
        val expandY = (seed.height() * 2.1f).toInt().coerceAtLeast(20)
        return Rect(
            (seed.left - expandX).coerceAtLeast(0),
            (seed.top - expandY).coerceAtLeast(0),
            (seed.right + expandX).coerceAtMost(maxW),
            (seed.bottom + expandY).coerceAtMost(maxH)
        )
    }

    private fun chooseDownscale(seed: Rect): Int {
        val side = max(seed.width(), seed.height())
        return when {
            side < 140 -> 6
            side < 240 -> 7
            side < 360 -> 8
            side < 520 -> 9
            else -> 10
        }
    }

    private fun chooseSeedPoint(seedSmall: Rect, lumas: FloatArray, w: Int, h: Int): Pair<Int, Int> {
        val cx = ((seedSmall.left + seedSmall.right) / 2).coerceIn(0, w - 1)
        val cy = ((seedSmall.top + seedSmall.bottom) / 2).coerceIn(0, h - 1)
        val samples = ArrayList<Pair<Int, Float>>(9)

        for (y in (cy - 1)..(cy + 1)) {
            for (x in (cx - 1)..(cx + 1)) {
                if (x !in 0 until w || y !in 0 until h) continue
                val idx = y * w + x
                samples.add(idx to lumas[idx])
            }
        }
        if (samples.isEmpty()) return cx to cy

        samples.sortBy { it.second }
        val medianIndex = samples[samples.size / 2].first
        return (medianIndex % w) to (medianIndex / w)
    }

    private fun scaleRectDown(src: Rect, srcW: Int, srcH: Int, dstW: Int, dstH: Int): Rect {
        val left = (src.left * dstW / srcW).coerceIn(0, dstW - 1)
        val top = (src.top * dstH / srcH).coerceIn(0, dstH - 1)
        val right = ((src.right * dstW + srcW - 1) / srcW).coerceIn(left + 1, dstW)
        val bottom = ((src.bottom * dstH + srcH - 1) / srcH).coerceIn(top + 1, dstH)
        return Rect(left, top, right, bottom)
    }

    private fun hasStrongEdge(lumas: FloatArray, x: Int, y: Int, w: Int, h: Int, edgeTol: Float): Boolean {
        val idx = y * w + x
        if (x + 1 < w && abs(lumas[idx] - lumas[idx + 1]) > edgeTol) return true
        if (y + 1 < h && abs(lumas[idx] - lumas[idx + w]) > edgeTol) return true
        return false
    }

    private fun matches(
        color: Int,
        luma: Float,
        seedColor: Int,
        seedLuma: Float,
        lumaTol: Float,
        colorTolSq: Float
    ): Boolean {
        if (abs(luma - seedLuma) > lumaTol) return false
        return rgbDistanceSq(color, seedColor) <= colorTolSq
    }

    private fun fallbackRect(seedRect: Rect, maxW: Int, maxH: Int): Rect {
        val seed = clampRect(seedRect, maxW, maxH)
        val inflateX = (seed.width() * 0.60f).toInt().coerceAtLeast(8)
        val inflateY = (seed.height() * 0.90f).toInt().coerceAtLeast(10)

        val inflated = Rect(
            (seed.left - inflateX).coerceAtLeast(0),
            (seed.top - inflateY).coerceAtLeast(0),
            (seed.right + inflateX).coerceAtMost(maxW),
            (seed.bottom + inflateY).coerceAtMost(maxH)
        )

        val seedArea = (seed.width() * seed.height()).coerceAtLeast(1)
        val maxArea = seedArea * 6
        val inflatedArea = (inflated.width() * inflated.height()).coerceAtLeast(1)
        if (inflatedArea <= maxArea) return inflated

        val ratio = sqrt(maxArea.toFloat() / inflatedArea.toFloat()).coerceIn(0f, 1f)
        val halfW = (inflated.width() * ratio / 2f).toInt().coerceAtLeast(seed.width() / 2)
        val halfH = (inflated.height() * ratio / 2f).toInt().coerceAtLeast(seed.height() / 2)
        val cx = inflated.centerX()
        val cy = inflated.centerY()
        return Rect(
            (cx - halfW).coerceAtLeast(0),
            (cy - halfH).coerceAtLeast(0),
            (cx + halfW).coerceAtMost(maxW),
            (cy + halfH).coerceAtMost(maxH)
        )
    }

    private fun clampRect(rect: Rect, maxW: Int, maxH: Int): Rect {
        val left = rect.left.coerceIn(0, maxW - 1)
        val top = rect.top.coerceIn(0, maxH - 1)
        val right = rect.right.coerceIn(left + 1, maxW)
        val bottom = rect.bottom.coerceIn(top + 1, maxH)
        return Rect(left, top, right, bottom)
    }

    private fun rgbDistanceSq(c1: Int, c2: Int): Float {
        val dr = (Color.red(c1) - Color.red(c2)).toFloat()
        val dg = (Color.green(c1) - Color.green(c2)).toFloat()
        val db = (Color.blue(c1) - Color.blue(c2)).toFloat()
        return dr * dr + dg * dg + db * db
    }

    private fun luminance(color: Int): Float {
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }
}
