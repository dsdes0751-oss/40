package com.tuna.proj_01

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.pow
import kotlin.math.sqrt

class ResizableCaptureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface OnRectChangeListener {
        fun onRectChanged(rect: Rect?)
        fun onActionUp()
        fun onActionDown()
    }

    var listener: OnRectChangeListener? = null
    var selectedRect: Rect? = null
        private set

    private var screenWidth = 0
    private var screenHeight = 0
    private var windowX = 0
    private var windowY = 0

    private val handleOffset = 50f
    private val centerCrossSize = 30f

    private val borderPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val handlePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val handleStrokePaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val centerPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }

    private val handleRadius = 25f

    private val MODE_NONE = 0
    private val MODE_DRAWING = 1
    private val MODE_MOVING = 2
    private val MODE_RESIZE_TL = 3
    private val MODE_RESIZE_TR = 4
    private val MODE_RESIZE_BL = 5
    private val MODE_RESIZE_BR = 6

    private var currentMode = MODE_NONE
    private var startRawX = 0f
    private var startRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f

    init {
        val metrics = context.resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
    }

    fun setScreenSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        screenWidth = width
        screenHeight = height
        selectedRect?.let {
            clampRectToScreen(it)
            listener?.onRectChanged(it)
        }
        invalidate()
    }

    fun setWindowFrame(x: Int, y: Int) {
        windowX = x
        windowY = y
        invalidate()
    }

    fun setInitialRect(rect: Rect?) {
        selectedRect = rect?.let { Rect(it) }
        selectedRect?.let { clampRectToScreen(it) }
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val rawX = event.rawX
        val rawY = event.rawY

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                listener?.onActionDown()

                val hit = hitTest(rawX, rawY)
                if (hit != MODE_NONE) {
                    currentMode = hit
                    lastRawX = rawX
                    lastRawY = rawY
                    invalidate()
                    return true
                }

                if (selectedRect != null) {
                    return false
                }

                currentMode = MODE_DRAWING
                startRawX = rawX
                startRawY = rawY
                lastRawX = rawX
                lastRawY = rawY
                selectedRect = null
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = rawX - lastRawX
                val dy = rawY - lastRawY

                when (currentMode) {
                    MODE_DRAWING -> updateRectFromDrag(startRawX, startRawY, rawX, rawY)
                    MODE_MOVING -> selectedRect?.offset(dx.toInt(), dy.toInt())
                    MODE_RESIZE_TL -> selectedRect?.let { it.left += dx.toInt(); it.top += dy.toInt() }
                    MODE_RESIZE_TR -> selectedRect?.let { it.right += dx.toInt(); it.top += dy.toInt() }
                    MODE_RESIZE_BL -> selectedRect?.let { it.left += dx.toInt(); it.bottom += dy.toInt() }
                    MODE_RESIZE_BR -> selectedRect?.let { it.right += dx.toInt(); it.bottom += dy.toInt() }
                }

                if (selectedRect != null) {
                    if (currentMode != MODE_DRAWING) normalizeRect(selectedRect!!)
                    clampRectToScreen(selectedRect!!)
                    listener?.onRectChanged(selectedRect)
                }

                lastRawX = rawX
                lastRawY = rawY
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                currentMode = MODE_NONE
                listener?.onActionUp()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        selectedRect?.let { screenRect ->
            val localRect = Rect(
                screenRect.left - windowX,
                screenRect.top - windowY,
                screenRect.right - windowX,
                screenRect.bottom - windowY
            )

            canvas.drawRect(localRect, borderPaint)

            val cx = localRect.centerX().toFloat()
            val cy = localRect.centerY().toFloat()
            canvas.drawLine(cx - centerCrossSize, cy, cx + centerCrossSize, cy, centerPaint)
            canvas.drawLine(cx, cy - centerCrossSize, cx, cy + centerCrossSize, centerPaint)

            drawHandle(canvas, localRect.left + handleOffset, localRect.top + handleOffset)
            drawHandle(canvas, localRect.right - handleOffset, localRect.top + handleOffset)
            drawHandle(canvas, localRect.left + handleOffset, localRect.bottom - handleOffset)
            drawHandle(canvas, localRect.right - handleOffset, localRect.bottom - handleOffset)
        }
    }

    private fun clampRectToScreen(rect: Rect) {
        if (rect.left < 0) {
            rect.right += (0 - rect.left)
            rect.left = 0
        }
        if (rect.top < 0) {
            rect.bottom += (0 - rect.top)
            rect.top = 0
        }
        if (rect.right > screenWidth) {
            rect.left -= (rect.right - screenWidth)
            rect.right = screenWidth
        }
        if (rect.bottom > screenHeight) {
            rect.top -= (rect.bottom - screenHeight)
            rect.bottom = screenHeight
        }

        rect.left = rect.left.coerceIn(0, screenWidth)
        rect.right = rect.right.coerceIn(0, screenWidth)
        rect.top = rect.top.coerceIn(0, screenHeight)
        rect.bottom = rect.bottom.coerceIn(0, screenHeight)

        val minSize = (handleOffset * 2.5).toInt()
        if (rect.width() < minSize) rect.right = (rect.left + minSize).coerceAtMost(screenWidth)
        if (rect.height() < minSize) rect.bottom = (rect.top + minSize).coerceAtMost(screenHeight)

        if (rect.width() < minSize) rect.left = (rect.right - minSize).coerceAtLeast(0)
        if (rect.height() < minSize) rect.top = (rect.bottom - minSize).coerceAtLeast(0)
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float) {
        canvas.drawCircle(x, y, handleRadius, handlePaint)
        canvas.drawCircle(x, y, handleRadius, handleStrokePaint)
    }

    private fun updateRectFromDrag(x1: Float, y1: Float, x2: Float, y2: Float) {
        var left = x1.coerceAtMost(x2).toInt()
        var top = y1.coerceAtMost(y2).toInt()
        var right = x1.coerceAtLeast(x2).toInt()
        var bottom = y1.coerceAtLeast(y2).toInt()

        left = left.coerceIn(0, screenWidth)
        right = right.coerceIn(0, screenWidth)
        top = top.coerceIn(0, screenHeight)
        bottom = bottom.coerceIn(0, screenHeight)

        selectedRect = Rect(left, top, right, bottom)
    }

    private fun normalizeRect(rect: Rect) {
        if (rect.left > rect.right) {
            val temp = rect.left
            rect.left = rect.right
            rect.right = temp
        }
        if (rect.top > rect.bottom) {
            val temp = rect.top
            rect.top = rect.bottom
            rect.bottom = temp
        }
    }

    private fun hitTest(rawX: Float, rawY: Float): Int {
        val rect = selectedRect ?: return MODE_NONE
        val touchRadius = handleRadius * 2.5

        if (isNear(rawX, rawY, rect.left + handleOffset, rect.top + handleOffset, touchRadius)) return MODE_RESIZE_TL
        if (isNear(rawX, rawY, rect.right - handleOffset, rect.top + handleOffset, touchRadius)) return MODE_RESIZE_TR
        if (isNear(rawX, rawY, rect.left + handleOffset, rect.bottom - handleOffset, touchRadius)) return MODE_RESIZE_BL
        if (isNear(rawX, rawY, rect.right - handleOffset, rect.bottom - handleOffset, touchRadius)) return MODE_RESIZE_BR

        if (rect.contains(rawX.toInt(), rawY.toInt())) return MODE_MOVING
        return MODE_NONE
    }

    private fun isNear(x1: Float, y1: Float, x2: Float, y2: Float, radius: Double): Boolean {
        return sqrt((x1 - x2).pow(2) + (y1 - y2).pow(2)) <= radius
    }
}
