package com.tuna.proj_01

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.google.mlkit.vision.text.Text
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object MangaProcessor {

    enum class MergePolicy {
        NONE,
        CONSERVATIVE,
        AGGRESSIVE
    }

    fun processOCRResult(
        visionText: Text,
        sourceLang: String,
        policy: MergePolicy = MergePolicy.CONSERVATIVE
    ): List<MangaBlock> {
        val isRTL = sourceLang == "Japanese" || sourceLang == "Chinese"

        val sortedBlocks = visionText.textBlocks.sortedWith(Comparator { b1, b2 ->
            val box1 = b1.boundingBox ?: return@Comparator 0
            val box2 = b2.boundingBox ?: return@Comparator 0

            val overlapTop = max(box1.top, box2.top)
            val overlapBottom = min(box1.bottom, box2.bottom)
            val overlapHeight = overlapBottom - overlapTop
            val minHeight = min(box1.height(), box2.height())
            val isSameLine = overlapHeight > (minHeight * 0.5f)

            if (isSameLine) {
                if (isRTL) box2.centerX() - box1.centerX() else box1.centerX() - box2.centerX()
            } else {
                box1.top - box2.top
            }
        })

        var idCounter = 0
        val rawBlocks = mutableListOf<MangaBlock>()

        sortedBlocks.forEach { block ->
            val rect = block.boundingBox ?: return@forEach
            val rawText = preprocessText(block.text)
            if (rawText.isBlank()) return@forEach

            rawBlocks.add(
                MangaBlock(
                    id = idCounter++,
                    pageIndex = 0,
                    originalText = rawText,
                    translatedText = "",
                    boundingBox = Rect(rect),
                    lineBoxes = if (block.lines.isNotEmpty()) block.lines.mapNotNull { it.boundingBox } else listOf(rect),
                    isVertical = rect.height() > rect.width()
                )
            )
        }

        return mergeCloseBlocks(rawBlocks, isRTL, policy)
    }

    private fun preprocessText(raw: String): String {
        val text = raw.replace("\n", " ").replace("\\s+".toRegex(), " ").trim()
        if (text.isBlank()) return ""
        if (text.matches(Regex("^[|!lI1\\-_=+\\.\\s]+$"))) return ""
        if (text.matches(Regex("^[0-9]+$"))) return ""
        return text
    }

    private fun mergeCloseBlocks(blocks: List<MangaBlock>, isRTL: Boolean, policy: MergePolicy): List<MangaBlock> {
        if (policy == MergePolicy.NONE) return blocks

        val active = blocks.toMutableList()
        var merged: Boolean

        do {
            merged = false
            var i = 0
            while (i < active.size) {
                var j = i + 1
                while (j < active.size) {
                    val b1 = active[i]
                    val b2 = active[j]
                    if (shouldMerge(b1, b2, isRTL, policy)) {
                        active[i] = performMerge(b1, b2)
                        active.removeAt(j)
                        merged = true
                    } else {
                        j++
                    }
                }
                i++
            }
        } while (merged)

        return active
    }

    private fun performMerge(b1: MangaBlock, b2: MangaBlock): MangaBlock {
        val mergedRect = Rect(b1.boundingBox).apply { union(b2.boundingBox) }
        return b1.copy(
            originalText = (b1.originalText + " " + b2.originalText).trim(),
            boundingBox = mergedRect,
            lineBoxes = b1.lineBoxes + b2.lineBoxes,
            isVertical = mergedRect.height() > mergedRect.width()
        )
    }

    private fun shouldMerge(b1: MangaBlock, b2: MangaBlock, isRTL: Boolean, policy: MergePolicy): Boolean {
        val r1 = b1.boundingBox
        val r2 = b2.boundingBox

        val minDim = min(min(r1.width(), r1.height()), min(r2.width(), r2.height())).toFloat().coerceAtLeast(1f)
        val centerDist = centerDistance(r1, r2)
        val overlapV = verticalOverlapRatio(r1, r2)
        val overlapH = horizontalOverlapRatio(r1, r2)
        val overlapRatio = max(overlapV, overlapH)
        val gap = minAxisGap(r1, r2)

        val union = Rect(r1).apply { union(r2) }
        val unionArea = union.width().toFloat() * union.height().toFloat()
        val areaSum = (r1.width() * r1.height() + r2.width() * r2.height()).toFloat().coerceAtLeast(1f)
        val unionGrowth = unionArea / areaSum

        val scale = if (policy == MergePolicy.AGGRESSIVE) 1.25f else 1f
        if (centerDist > minDim * (0.35f * scale)) return false
        if (overlapRatio < if (policy == MergePolicy.AGGRESSIVE) 0.55f else 0.6f) return false
        if (unionGrowth > if (policy == MergePolicy.AGGRESSIVE) 1.45f else 1.35f) return false
        if (gap > minDim * (0.2f * scale)) return false

        if (isRTL) {
            val verticalBias = overlapV >= 0.6f
            val centerBias = abs(r1.centerX() - r2.centerX()) <= minDim * 0.5f
            return verticalBias || centerBias
        }
        return true
    }

    private fun centerDistance(r1: Rect, r2: Rect): Float {
        val dx = (r1.centerX() - r2.centerX()).toFloat()
        val dy = (r1.centerY() - r2.centerY()).toFloat()
        return sqrt(dx * dx + dy * dy)
    }

    private fun verticalOverlapRatio(r1: Rect, r2: Rect): Float {
        val overlap = max(0, min(r1.bottom, r2.bottom) - max(r1.top, r2.top)).toFloat()
        val base = min(r1.height(), r2.height()).toFloat().coerceAtLeast(1f)
        return overlap / base
    }

    private fun horizontalOverlapRatio(r1: Rect, r2: Rect): Float {
        val overlap = max(0, min(r1.right, r2.right) - max(r1.left, r2.left)).toFloat()
        val base = min(r1.width(), r2.width()).toFloat().coerceAtLeast(1f)
        return overlap / base
    }

    private fun minAxisGap(r1: Rect, r2: Rect): Float {
        val gapX = max(0, max(r1.left, r2.left) - min(r1.right, r2.right))
        val gapY = max(0, max(r1.top, r2.top) - min(r1.bottom, r2.bottom))
        return min(gapX, gapY).toFloat()
    }

    fun drawAllBlocks(canvas: Canvas, originalBitmap: Bitmap, blocks: List<MangaBlock>, sourceLang: String) {
        drawMasking(canvas, originalBitmap, blocks)
        drawTranslation(canvas, blocks, sourceLang)
    }

    fun drawMasking(canvas: Canvas, originalBitmap: Bitmap, blocks: List<MangaBlock>) {
        val paint = StyleManager.maskingPaint

        blocks.forEach { block ->
            paint.color = getDominantBackgroundColor(originalBitmap, block.boundingBox)
            block.lineBoxes.forEach { lineBox ->
                val rectF = RectF(lineBox)
                rectF.inset(-10f, -10f)
                canvas.drawRoundRect(rectF, 15f, 15f, paint)
            }
        }
    }

    private fun createBlurPatch(src: Bitmap, rect: Rect): Bitmap? {
        return try {
            var current = Bitmap.createBitmap(src, rect.left, rect.top, rect.width(), rect.height())
            val maxSide = max(rect.width(), rect.height())
            val downscaleFactor = when {
                maxSide > 1600 -> 14
                maxSide > 1200 -> 12
                maxSide > 900 -> 10
                maxSide > 500 -> 8
                else -> 6
            }

            repeat(2) {
                val down = Bitmap.createScaledBitmap(
                    current,
                    (current.width / downscaleFactor).coerceAtLeast(1),
                    (current.height / downscaleFactor).coerceAtLeast(1),
                    true
                )
                val up = Bitmap.createScaledBitmap(down, current.width, current.height, true)
                if (!current.isRecycled) current.recycle()
                down.recycle()
                current = up
            }
            current
        } catch (_: Exception) {
            null
        }
    }

    fun drawTranslation(canvas: Canvas, blocks: List<MangaBlock>, sourceLang: String) {
        val textPaint = StyleManager.textPaint
        val occupiedRegions = mutableListOf<RectF>()

        val optimalSizes = mutableListOf<Float>()
        val renderList = mutableListOf<Pair<MangaBlock, Rect>>()

        blocks.forEach { block ->
            if (block.translatedText.isNotBlank()) {
                val bounds = getDrawingBounds(block, sourceLang)
                val maxPossibleSize = calculateOptimalFontSize(block.translatedText, bounds, textPaint)
                optimalSizes.add(maxPossibleSize)
                renderList.add(Pair(block, bounds))
            }
        }
        if (optimalSizes.isEmpty()) return

        val validSizes = optimalSizes.filter { it > 18f }
        val averageSize = if (validSizes.isNotEmpty()) validSizes.average().toFloat() else 33f
        val standardFontSize = averageSize.coerceIn(28f, 58f)

        renderList.forEachIndexed { index, (block, bounds) ->
            val myMax = optimalSizes[index]
            val finalFontSize = min(myMax, standardFontSize)
            drawSingleBlockText(canvas, block, bounds, occupiedRegions, textPaint, finalFontSize)
        }
    }

    private fun drawSingleBlockText(
        canvas: Canvas,
        block: MangaBlock,
        bounds: Rect,
        occupiedRegions: MutableList<RectF>,
        basePaint: TextPaint,
        fontSize: Float
    ) {
        var currentFontSize = fontSize
        var layout: StaticLayout? = null
        var finalRect: RectF? = null
        var retries = 0

        val workingPaint = TextPaint(basePaint)

        while (retries < 3) {
            workingPaint.textSize = currentFontSize
            val textWidthLimit = (bounds.width() * 0.95f).toInt().coerceAtLeast(20)

            layout = createStaticLayout(block.translatedText, workingPaint, textWidthLimit)

            val w = layout.width.toFloat()
            val h = layout.height.toFloat()

            var x = bounds.centerX() - (w / 2f)
            var y = bounds.centerY() - (h / 2f)

            x = x.coerceIn(0f, (canvas.width - w))
            y = y.coerceIn(0f, (canvas.height - h))

            val candidate = RectF(x, y, x + w, y + h)

            if (adjustPositionForCollision(candidate, occupiedRegions, canvas.height)) {
                finalRect = candidate
                break
            }
            currentFontSize *= 0.85f
            retries++
        }

        if (finalRect != null && layout != null) {
            occupiedRegions.add(finalRect)
            canvas.save()
            canvas.translate(finalRect.left, finalRect.top)

            workingPaint.style = Paint.Style.STROKE
            workingPaint.strokeWidth = 4f
            workingPaint.color = Color.WHITE
            layout.draw(canvas)

            workingPaint.style = Paint.Style.FILL
            workingPaint.strokeWidth = 0f
            workingPaint.color = Color.BLACK
            layout.draw(canvas)

            canvas.restore()
        }
    }

    private fun getDrawingBounds(block: MangaBlock, sourceLang: String): Rect {
        val isRTL = sourceLang == "Japanese" || sourceLang == "Chinese"
        if (isRTL && block.isVertical) {
            val area = block.boundingBox.width() * block.boundingBox.height()
            val side = sqrt(area.toDouble()).toInt()
            val cx = block.boundingBox.centerX()
            val cy = block.boundingBox.centerY()
            return Rect(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2)
        }
        return block.boundingBox
    }

    private fun getDominantBackgroundColor(bitmap: Bitmap, rect: Rect): Int {
        val histogram = mutableMapOf<Int, Int>()
        val step = 5

        val l = rect.left.coerceIn(0, bitmap.width - 1)
        val r = rect.right.coerceIn(0, bitmap.width - 1)
        val t = rect.top.coerceIn(0, bitmap.height - 1)
        val b = rect.bottom.coerceIn(0, bitmap.height - 1)

        for (x in l..r step step) {
            histogram.merge(bitmap.getPixel(x, t), 1, Int::plus)
            histogram.merge(bitmap.getPixel(x, b), 1, Int::plus)
        }
        for (y in t..b step step) {
            histogram.merge(bitmap.getPixel(l, y), 1, Int::plus)
            histogram.merge(bitmap.getPixel(r, y), 1, Int::plus)
        }
        return histogram.maxByOrNull { it.value }?.key ?: Color.WHITE
    }

    private fun estimateBubbleBounds(originalBitmap: Bitmap, seedRect: Rect): Rect {
        val expandX = (seedRect.width() * 0.60f).toInt().coerceAtLeast(8)
        val expandY = (seedRect.height() * 0.90f).toInt().coerceAtLeast(10)

        val expanded = Rect(
            (seedRect.left - expandX).coerceAtLeast(0),
            (seedRect.top - expandY).coerceAtLeast(0),
            (seedRect.right + expandX).coerceAtMost(originalBitmap.width),
            (seedRect.bottom + expandY).coerceAtMost(originalBitmap.height)
        )

        val seedArea = (seedRect.width() * seedRect.height()).coerceAtLeast(1)
        val maxArea = seedArea * 4
        val curArea = (expanded.width() * expanded.height()).coerceAtLeast(1)
        if (curArea <= maxArea) return expanded

        val ratio = sqrt(maxArea.toFloat() / curArea.toFloat())
        val halfW = (expanded.width() * ratio / 2f).toInt().coerceAtLeast(seedRect.width() / 2)
        val halfH = (expanded.height() * ratio / 2f).toInt().coerceAtLeast(seedRect.height() / 2)
        val cx = expanded.centerX()
        val cy = expanded.centerY()
        return Rect(
            (cx - halfW).coerceAtLeast(0),
            (cy - halfH).coerceAtLeast(0),
            (cx + halfW).coerceAtMost(originalBitmap.width),
            (cy + halfH).coerceAtMost(originalBitmap.height)
        )
    }

    private fun sampleAverageColor(bitmap: Bitmap, rect: Rect): Int {
        val step = max(2, min(rect.width(), rect.height()) / 12)
        var r = 0L
        var g = 0L
        var b = 0L
        var count = 0L

        var y = rect.top
        while (y < rect.bottom) {
            var x = rect.left
            while (x < rect.right) {
                val c = bitmap.getPixel(x, y)
                r += Color.red(c)
                g += Color.green(c)
                b += Color.blue(c)
                count++
                x += step
            }
            y += step
        }

        if (count == 0L) return Color.WHITE
        return Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
    }

    private fun averageLuminance(bitmap: Bitmap, rect: Rect): Float {
        if (rect.width() < 2 || rect.height() < 2) return 1f

        val step = max(2, min(rect.width(), rect.height()) / 10)
        var sum = 0f
        var count = 0

        var y = rect.top
        while (y < rect.bottom) {
            var x = rect.left
            while (x < rect.right) {
                sum += luminance(bitmap.getPixel(x, y))
                count++
                x += step
            }
            y += step
        }
        return if (count == 0) 1f else sum / count
    }

    private fun luminance(color: Int): Float {
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    private fun calculateOptimalFontSize(text: String, rect: Rect, paint: TextPaint): Float {
        val w = (rect.width() * 0.9f).toInt().coerceAtLeast(10)
        val h = (rect.height() * 0.9f).toInt().coerceAtLeast(10)

        val maxLimit = if (text.length < 5) 92f else 76f
        var low = 16f
        var high = maxLimit
        var best = 16f
        val tempPaint = TextPaint(paint)

        repeat(7) {
            val mid = (low + high) / 2f
            tempPaint.textSize = mid
            val layout = createStaticLayout(text, tempPaint, w)
            if (layout.height <= h) {
                best = mid
                low = mid
            } else {
                high = mid
            }
        }
        return best
    }

    @SuppressLint("WrongConstant")
    private fun createStaticLayout(text: String, paint: TextPaint, width: Int): StaticLayout {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .build()
        }
        @Suppress("DEPRECATION")
        return StaticLayout(text, paint, width, Layout.Alignment.ALIGN_CENTER, 1f, 0f, false)
    }

    private fun adjustPositionForCollision(rect: RectF, occupied: List<RectF>, maxH: Int): Boolean {
        for (occ in occupied) {
            if (RectF.intersects(rect, occ)) {
                val push = occ.bottom - rect.top + 6f
                rect.offset(0f, push)
                if (rect.bottom > maxH) return false
            }
        }
        return true
    }
}
