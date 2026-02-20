package com.tuna.proj_01

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ScreenTranslationService : Service() {

    companion object {
        const val ACTION_START = "com.tuna.proj_01.ACTION_START"
        const val ACTION_START_AUTO = "com.tuna.proj_01.ACTION_START_AUTO"
        const val ACTION_STOP = "com.tuna.proj_01.ACTION_STOP"
        const val ACTION_UPDATE_LANG = "com.tuna.proj_01.UPDATE_LANG"
        const val ACTION_UPDATE_MODEL = "com.tuna.proj_01.UPDATE_MODEL" // [추가] 모델 변경 액션
        const val ACTION_UPDATE_TARGET_LANG = "com.tuna.proj_01.UPDATE_TARGET_LANG"
        const val ACTION_SERVICE_STOPPED = "com.tuna.proj_01.SERVICE_STOPPED"
        const val EXTRA_TRANSLATION_MODE = "extra_translation_mode"
        const val MODE_VN_FAST = "VN_FAST"
        const val EXTRA_VN_TARGET_LANG = "extra_vn_target_lang"
        const val EXTRA_VN_SOURCE_LANG = "extra_vn_source_lang"
        private const val TAG = "ScreenTranslation"
        private const val VN_INPUT_CHAR_LIMIT = 90
        private const val DEFAULT_CHANGE_THRESHOLD = 24
        private const val VN_CHANGE_THRESHOLD = 16
        private const val DEFAULT_POLL_INTERVAL_MS = 500L
        private const val VN_POLL_INTERVAL_MS = 300L
        private const val DEFAULT_STABLE_DELAY_MS = 700L
        private const val VN_STABLE_DELAY_MS = 600L
        // [변경] MAX_DETECTED_CHAR_COUNT 상수 제거, getMaxDetectedCharCount()로 동적 적용
    }

    private enum class TranslationMode {
        DEFAULT,
        VN_FAST
    }

    private data class AutoRegionSignature(
        val avgLuma: Int,
        val edgeLuma: Int
    )

    private lateinit var windowManager: WindowManager
    private lateinit var floatingButton: ImageView

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }

    private var captureEditorOverlayView: ResizableCaptureView? = null
    private var captureEditorParams: WindowManager.LayoutParams? = null
    private var captureControlsOverlayView: View? = null
    private var captureControlsParams: WindowManager.LayoutParams? = null
    private var resultOverlayView: ImageView? = null
    private var indicatorOverlay: View? = null
    private var loadingOverlay: View? = null
    private var loadingTextView: TextView? = null

    private var currentToastView: View? = null

    private var loadingParams: WindowManager.LayoutParams? = null
    private var floatingParams: WindowManager.LayoutParams? = null

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenDensity: Int = 0
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0

    private var resultCode: Int = 0
    private var resultData: Intent? = null

    private var reusableBitmap: Bitmap? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private val translationQueue = mutableListOf<MangaBlock>()
    private var sourceLang: String = "Japanese"
    private var targetLang: String = "Korean"
    private var currentModelTier: String = "ADVANCED" // [추가] 기본값 ADVANCED
    private var translationMode: TranslationMode = TranslationMode.DEFAULT
    private var vnTargetLangCode: String = "KO"
    private var vnSourceLangCode: String? = null

    // [변경] 대상 언어별 최대 글자 수 제한 (영어: 425, 기본: 250)
    private fun getMaxDetectedCharCount(): Int {
        return if (targetLang == "English") 425 else 250
    }
    private var targetCaptureRect: Rect? = null

    private var currentOverlayBitmap: Bitmap? = null
    private var currentOverlayCanvas: Canvas? = null

    private var isRunning = false
    private var isCaptureInProgress = false
    private var isAutoTranslateEnabled = false
    private var autoTranslateJob: Job? = null
    private var isAutoMode = false  // [추가] 자동 화면번역 모드 여부
    private var autoNoiseEma = 0.0
    private var autoDynamicThreshold = DEFAULT_CHANGE_THRESHOLD

    private var lastClickTime: Long = 0
    private val doubleClickDelay = 300L
    private val TOUCH_SLOP = 30f
    private var isLongPressJob: Job? = null
    private var singleClickJob: Job? = null

    private lateinit var gestureDetector: GestureDetector

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        updateScreenMetrics()

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                removeOverlayView()
                return true
            }
        })

        createNotificationChannel()
    }

    // 화면 해상도 정보를 최신값으로 갱신하는 함수
    private fun updateScreenMetrics() {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenDensity = metrics.densityDpi
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        captureEditorOverlayView?.setScreenSize(screenWidth, screenHeight)
        Log.d(TAG, "Screen metrics updated: ${screenWidth}x${screenHeight}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        val action = intent.action

        when (action) {
            ACTION_STOP -> {
                stopServiceAndNotify(showToast = false)
                return START_NOT_STICKY
            }
            ACTION_UPDATE_LANG -> {
                val newLang = intent.getStringExtra("sourceLang")
                if (newLang != null) {
                    sourceLang = newLang
                    showCustomToast("번역 언어 변경: $sourceLang")
                }
                return START_NOT_STICKY
            }
            ACTION_UPDATE_MODEL -> { // [추가] 모델 변경 처리
                val newModel = intent.getStringExtra("modelTier")
                if (newModel != null) {
                    currentModelTier = newModel
                    val modelName = when(currentModelTier) {
                        "STANDARD" -> "일반 번역 (무료)"
                        "ADVANCED" -> "고급 번역 (실버)"
                        "PRO" -> "프로 번역 (골드)"
                        else -> "알 수 없음"
                    }
                    showCustomToast("모델 변경: $modelName")
                }
                return START_NOT_STICKY
            }
            ACTION_UPDATE_TARGET_LANG -> {
                val newTargetLang = intent.getStringExtra("targetLang")
                if (newTargetLang != null) {
                    targetLang = newTargetLang
                    showCustomToast("번역 대상 언어 변경: $targetLang")
                }
                return START_NOT_STICKY
            }
        }

        val code = intent.getIntExtra("resultCode", 0)
        val data = intent.getParcelableExtra<Intent>("data")

        if (code != 0 && data != null) {
            resultCode = code
            resultData = data
            val newLang = intent.getStringExtra("sourceLang")
            if (newLang != null) sourceLang = newLang
            val initialTargetLang = intent.getStringExtra("targetLang")
            if (initialTargetLang != null) targetLang = initialTargetLang
            // 초기 실행 시 모델 설정값 수신
            val initialModel = intent.getStringExtra("modelTier")
            if (initialModel != null) currentModelTier = initialModel
            translationMode = if (intent.getStringExtra(EXTRA_TRANSLATION_MODE) == MODE_VN_FAST) {
                TranslationMode.VN_FAST
            } else {
                TranslationMode.DEFAULT
            }
            val vnTarget = intent.getStringExtra(EXTRA_VN_TARGET_LANG)
            if (!vnTarget.isNullOrBlank()) vnTargetLangCode = vnTarget
            val vnSource = intent.getStringExtra(EXTRA_VN_SOURCE_LANG)
            vnSourceLangCode = vnSource

            // [추가] 자동 화면번역 모드 설정
            isAutoMode = action == ACTION_START_AUTO

            if (!isRunning) {
                isRunning = true
                startForegroundServiceNotification()
                createFloatingButton()
                if (translationMode == TranslationMode.VN_FAST && isAutoMode) {
                    showCaptureAreaOverlay()
                }
            } else {
                restoreOverlays()
                if (translationMode == TranslationMode.VN_FAST && isAutoMode) {
                    showCaptureAreaOverlay()
                }
            }
        } else {
            if (!isRunning) stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startForegroundServiceNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, createNotification())
        }
    }

    private fun stopServiceAndNotify(showToast: Boolean = true) {
        sendBroadcast(Intent(ACTION_SERVICE_STOPPED).setPackage(packageName))

        if (showToast) {
            safeRemoveView(floatingButton)
            removeOverlayView()
            removeIndicatorOverlay()
            hideLoadingOverlay()
            removeCaptureAreaOverlay(keepFloatingButton = false)

            showCustomToast("화면 번역 서비스가 종료되었습니다.")

            serviceScope.launch {
                delay(2000)
                stopSelf()
            }
        } else {
            stopSelf()
        }
    }

    private fun showCustomToast(message: String) {
        serviceScope.launch {
            safeRemoveView(currentToastView)

            try {
                val toastView = TextView(this@ScreenTranslationService).apply {
                    text = message
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    setPadding(40, 20, 40, 20)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 20f
                        setColor(0xCC000000.toInt())
                    }
                    elevation = 10f
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    y = 200
                }

                windowManager.addView(toastView, params)
                currentToastView = toastView

                delay(3500)

                if (currentToastView == toastView) {
                    safeRemoveView(toastView)
                    currentToastView = null
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun showLoadingOverlay() {
        if (loadingOverlay != null) return

        try {
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(50, 40, 50, 40)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 30f
                    setColor(0xDD000000.toInt())
                }
            }

            val progressBar = ProgressBar(this).apply {
                indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            }

            loadingTextView = TextView(this).apply {
                text = "텍스트 분석 중..."
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, 30, 0, 0)
            }

            container.addView(progressBar)
            container.addView(loadingTextView)

            loadingOverlay = container

            loadingParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }

            windowManager.addView(loadingOverlay, loadingParams)
            floatingButton.visibility = View.GONE

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateLoadingText(message: String) {
        loadingTextView?.text = message
    }

    private fun hideLoadingOverlay() {
        safeRemoveView(loadingOverlay)
        loadingOverlay = null
        loadingTextView = null
        loadingParams = null
    }

    private fun safeRemoveView(view: View?) {
        if (view != null && view.isAttachedToWindow) {
            try {
                windowManager.removeView(view)
            } catch (e: Exception) { }
        }
    }

    private fun showOverlayView(bitmap: Bitmap) {
        // [수정] 오프셋 문제 해결: FLAG_LAYOUT_NO_LIMITS 재적용 (좌표 통일)
        val params = WindowManager.LayoutParams(
            bitmap.width,
            bitmap.height,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or // 재추가: 화면 전체를 좌표 기준으로 사용
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        )

        // [중요] 컷아웃 영역까지 오버레이를 표시하도록 설정
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        if (targetCaptureRect != null) {
            params.gravity = Gravity.TOP or Gravity.START
            params.x = targetCaptureRect!!.left
            params.y = targetCaptureRect!!.top
        }

        if (resultOverlayView == null || resultOverlayView?.windowToken == null) {
            resultOverlayView = ImageView(this).apply {
                scaleType = ImageView.ScaleType.MATRIX
                isFocusable = true
                isFocusableInTouchMode = true

                setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_OUTSIDE) {
                        removeOverlayView()
                        true
                    } else {
                        gestureDetector.onTouchEvent(event)
                        true
                    }
                }

                setOnKeyListener { _, keyCode, event ->
                    if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                        removeOverlayView()
                        true
                    } else {
                        false
                    }
                }
            }
            resultOverlayView?.setImageBitmap(bitmap)

            try {
                windowManager.addView(resultOverlayView, params)
                resultOverlayView?.requestFocus()
            } catch (e: Exception) { e.printStackTrace() }

        } else {
            resultOverlayView?.setImageBitmap(bitmap)
            try {
                windowManager.updateViewLayout(resultOverlayView, params)
                resultOverlayView?.visibility = View.VISIBLE
                resultOverlayView?.requestFocus()
            } catch (e: Exception) {
                try {
                    windowManager.addView(resultOverlayView, params)
                } catch (e2: Exception) { e2.printStackTrace() }
            }
        }

        floatingButton.visibility = View.GONE
    }

    private fun loadCaptureSettings() {
        val prefs = getSharedPreferences("screen_trans_prefs", Context.MODE_PRIVATE)
        val isFixed = prefs.getBoolean("is_fixed_mode", false)
        if (isFixed) {
            val left = prefs.getInt("area_left", 0)
            val top = prefs.getInt("area_top", 0)
            val right = prefs.getInt("area_right", 0)
            val bottom = prefs.getInt("area_bottom", 0)
            if (right > left && bottom > top) targetCaptureRect = Rect(left, top, right, bottom)
            else targetCaptureRect = null
        } else {
            targetCaptureRect = null
        }
    }

    private fun saveCaptureSettings(rect: Rect, isFixed: Boolean) {
        val prefs = getSharedPreferences("screen_trans_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putInt("area_left", rect.left); putInt("area_top", rect.top)
            putInt("area_right", rect.right); putInt("area_bottom", rect.bottom)
            putBoolean("is_fixed_mode", isFixed); apply()
        }
        targetCaptureRect = if (isFixed) rect else null
        if (targetCaptureRect != null) showIndicatorOverlay(targetCaptureRect!!)
    }

    private fun showIndicatorOverlay(rect: Rect) {
        removeIndicatorOverlay()
        try {
            indicatorOverlay = View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(Color.TRANSPARENT); setStroke(6, Color.GREEN) }
            }
            val params = WindowManager.LayoutParams(
                rect.width(), rect.height(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
                // [수정] NO_LIMITS 플래그 적용
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = rect.left
                y = rect.top
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }

            windowManager.addView(indicatorOverlay, params)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun removeIndicatorOverlay() {
        safeRemoveView(indicatorOverlay)
        indicatorOverlay = null
    }

    private fun showCaptureAreaOverlay() {
        if (captureEditorOverlayView != null || captureControlsOverlayView != null) return
        removeIndicatorOverlay()
        updateScreenMetrics()

        try {
            val rectToDraw = targetCaptureRect ?: Rect(screenWidth / 2 - 300, screenHeight / 2 - 200, screenWidth / 2 + 300, screenHeight / 2 + 200)
                .apply {
                    left = left.coerceIn(0, screenWidth - 1)
                    right = right.coerceIn(left + 1, screenWidth)
                    top = top.coerceIn(0, screenHeight - 1)
                    bottom = bottom.coerceIn(top + 1, screenHeight)
                }

            val editorView = ResizableCaptureView(this).apply {
                setScreenSize(screenWidth, screenHeight)
                setInitialRect(rectToDraw)
            }

            val editorParams = WindowManager.LayoutParams(
                rectToDraw.width(),
                rectToDraw.height(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = rectToDraw.left
                y = rectToDraw.top
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                editorParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }

            val controlsView = LayoutInflater.from(this).inflate(R.layout.layout_capture_overlay, null)
            val btnConfirm = controlsView.findViewById<Button>(R.id.btn_confirm_area)
            val cbFix = controlsView.findViewById<CheckBox>(R.id.cb_fix_area)

            btnConfirm.text = "영역 설정 완료"
            val prefs = getSharedPreferences("screen_trans_prefs", Context.MODE_PRIVATE)
            cbFix.isChecked = prefs.getBoolean("is_fixed_mode", false)

            val controlsParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                controlsParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }

            captureEditorOverlayView = editorView
            captureEditorParams = editorParams
            captureControlsOverlayView = controlsView
            captureControlsParams = controlsParams

            editorView.listener = object : ResizableCaptureView.OnRectChangeListener {
                override fun onRectChanged(rect: Rect?) {
                    if (rect == null) return
                    updateCaptureEditorWindow(rect)
                    updateControlPanelPosition(rect, controlsView)
                }

                override fun onActionDown() {
                    controlsView.visibility = View.INVISIBLE
                }

                override fun onActionUp() {
                    if (editorView.selectedRect != null) {
                        controlsView.visibility = View.VISIBLE
                        updateControlPanelPosition(editorView.selectedRect!!, controlsView)
                    }
                }
            }

            btnConfirm.setOnClickListener {
                val rect = editorView.selectedRect
                if (rect != null && rect.width() > 50 && rect.height() > 50) {
                    val resultRect = Rect(rect)
                    val isFixed = cbFix.isChecked
                    saveCaptureSettings(resultRect, isFixed)
                    isAutoTranslateEnabled = false
                    targetCaptureRect = resultRect
                    removeCaptureAreaOverlay(keepFloatingButton = false)

                    if (isAutoMode) {
                        floatingButton.visibility = View.GONE
                        startCaptureWithCheck()
                    } else if (!isFixed) {
                        stopAutoTranslateMonitoring()
                        floatingButton.visibility = View.GONE
                        startCaptureWithCheck()
                    } else {
                        floatingButton.visibility = View.VISIBLE
                        showCustomToast("영역이 고정되었습니다. 돋보기를 눌러 번역하세요.")
                    }
                } else {
                    showCustomToast("영역을 더 크게 지정해주세요.")
                }
            }

            windowManager.addView(editorView, editorParams)
            editorView.setWindowFrame(editorParams.x, editorParams.y)
            windowManager.addView(controlsView, controlsParams)
            updateControlPanelPosition(rectToDraw, controlsView)
            floatingButton.visibility = View.GONE
        } catch (e: Exception) {
            e.printStackTrace()
            removeCaptureAreaOverlay(keepFloatingButton = true)
            showCustomToast("오버레이 실행 오류")
        }
    }

    private fun removeCaptureAreaOverlay(keepFloatingButton: Boolean = true) {
        safeRemoveView(captureEditorOverlayView)
        safeRemoveView(captureControlsOverlayView)
        captureEditorOverlayView = null
        captureEditorParams = null
        captureControlsOverlayView = null
        captureControlsParams = null
        if (keepFloatingButton) {
            floatingButton.visibility = View.VISIBLE
        }
    }

    private fun updateCaptureEditorWindow(rect: Rect) {
        val editorView = captureEditorOverlayView ?: return
        val params = captureEditorParams ?: return
        val width = rect.width().coerceAtLeast(1)
        val height = rect.height().coerceAtLeast(1)

        var changed = false
        if (params.x != rect.left) {
            params.x = rect.left
            changed = true
        }
        if (params.y != rect.top) {
            params.y = rect.top
            changed = true
        }
        if (params.width != width) {
            params.width = width
            changed = true
        }
        if (params.height != height) {
            params.height = height
            changed = true
        }

        if (changed && editorView.isAttachedToWindow) {
            try {
                windowManager.updateViewLayout(editorView, params)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        editorView.setWindowFrame(params.x, params.y)
    }

    private fun updateControlPanelPosition(rect: Rect, panel: View) {
        panel.post {
            val params = captureControlsParams ?: return@post
            val (newX, newY) = calculateControlPanelPosition(rect, panel.width, panel.height)
            params.x = newX
            params.y = newY

            if (panel.isAttachedToWindow) {
                try {
                    windowManager.updateViewLayout(panel, params)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            if (panel.visibility != View.VISIBLE) panel.visibility = View.VISIBLE
        }
    }

    private fun calculateControlPanelPosition(rect: Rect, panelW: Int, panelH: Int): Pair<Int, Int> {
        val safePanelW = panelW.coerceAtLeast(1)
        val safePanelH = panelH.coerceAtLeast(1)
        val margin = 20f

        var newX = rect.left + (rect.width() - safePanelW) / 2f
        newX = newX.coerceIn(10f, (screenWidth - safePanelW - 10).toFloat())

        var newY = rect.bottom + margin
        if (newY + safePanelH > screenHeight - 50) newY = rect.top - safePanelH - margin
        if (newY < 50) newY = rect.bottom - safePanelH - margin

        return Pair(newX.toInt(), newY.toInt())
    }

    private fun createFloatingButton() {
        floatingButton = ImageView(this).apply { setImageResource(android.R.drawable.ic_menu_search); setBackgroundColor(0x99000000.toInt()); setPadding(20, 20, 20, 20); isHapticFeedbackEnabled = true }
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        floatingParams = WindowManager.LayoutParams(150, 150, layoutFlag, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_SECURE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 100 }
        setTouchListener()
        try { windowManager.addView(floatingButton, floatingParams) } catch (e: Exception) {}
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setTouchListener() {
        var initialX = 0; var initialY = 0; var initialTouchX = 0f; var initialTouchY = 0f
        floatingButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = floatingParams!!.x; initialY = floatingParams!!.y
                    initialTouchX = event.rawX; initialTouchY = event.rawY
                    isLongPressJob = serviceScope.launch {
                        delay(800)
                        floatingButton.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        stopServiceAndNotify(showToast = true)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > TOUCH_SLOP || abs(dy) > TOUCH_SLOP) {
                        isLongPressJob?.cancel()
                        floatingParams!!.x = initialX + dx
                        floatingParams!!.y = initialY + dy
                        windowManager.updateViewLayout(floatingButton, floatingParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    isLongPressJob?.cancel()
                    if (abs(event.rawX - initialTouchX) < TOUCH_SLOP && abs(event.rawY - initialTouchY) < TOUCH_SLOP) {
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastClickTime < doubleClickDelay) {
                            singleClickJob?.cancel()
                            onDoubleClickAction()
                            lastClickTime = 0
                        } else {
                            lastClickTime = currentTime
                            singleClickJob = serviceScope.launch {
                                delay(doubleClickDelay)
                                performSingleClickAction()
                            }
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun performSingleClickAction() {
        if (resultCode == 0 || resultData == null) {
            showCustomToast("권한 오류: 앱을 다시 실행해주세요")
            return
        }
        loadCaptureSettings()

        if (targetCaptureRect != null) {
            showIndicatorOverlay(targetCaptureRect!!)
            if (isAutoMode) {
                // [변경] 자동 화면번역 모드: 자동 번역 실행
                toggleAutoTranslate()
            } else {
                floatingButton.visibility = View.GONE
                startCaptureWithCheck()
            }
        } else {
            stopAutoTranslateMonitoring()
            showCaptureAreaOverlay()
        }
    }

    private fun onDoubleClickAction() { showCaptureAreaOverlay() }

    private fun toggleAutoTranslate() {
        if (isAutoTranslateEnabled) {
            stopAutoTranslateMonitoring(showToast = true)
        } else {
            startAutoTranslateMonitoring()
        }
    }

    private fun startAutoTranslateMonitoring() {
        val rect = targetCaptureRect
        if (rect == null) {
            showCustomToast("먼저 번역 영역을 지정해주세요.")
            return
        }
        if (autoTranslateJob?.isActive == true) return

        isAutoTranslateEnabled = true
        resetAutoChangeLearning()
        showCustomToast("자동번역 시작: 영역 변화 감지 중")
        val pollInterval = if (translationMode == TranslationMode.VN_FAST) VN_POLL_INTERVAL_MS else DEFAULT_POLL_INTERVAL_MS
        val stableDelay = if (translationMode == TranslationMode.VN_FAST) VN_STABLE_DELAY_MS else DEFAULT_STABLE_DELAY_MS

        autoTranslateJob = serviceScope.launch {
            var lastSignature = captureSignatureForAuto(rect)
            if (lastSignature != null && !isCaptureInProgress) {
                Log.d(TAG, "Auto mode: initial translation trigger")
                triggerTranslationFromAuto()
            }

            var changedAt = 0L
            var waitingForStable = false

            while (isAutoTranslateEnabled) {
                delay(pollInterval)
                if (isCaptureInProgress) continue

                // [변경] 캡처 전 오버레이 숨김
                val signature = captureSignatureForAuto(rect)
                if (signature == null) continue

                if (lastSignature == null) { lastSignature = signature; continue }

                val changed = isRegionChanged(lastSignature, signature)
                if (changed) {
                    Log.d(TAG, "Auto mode: change detected (threshold=$autoDynamicThreshold)")
                    lastSignature = signature
                    changedAt = System.currentTimeMillis()
                    waitingForStable = true
                    continue
                }

                if (waitingForStable && System.currentTimeMillis() - changedAt >= stableDelay) {
                    waitingForStable = false
                    Log.d(TAG, "Auto mode: stable window reached, trigger translation")
                    triggerTranslationFromAuto()
                }
            }
        }
    }

    private fun stopAutoTranslateMonitoring(showToast: Boolean = false) {
        isAutoTranslateEnabled = false
        autoTranslateJob?.cancel()
        autoTranslateJob = null
        if (showToast) showCustomToast("자동번역이 종료되었습니다.")
    }

    private fun triggerTranslationFromAuto() {
        if (isCaptureInProgress) return
        removeOverlayView()
        floatingButton.visibility = View.VISIBLE
        startCaptureWithCheck()
    }

    private fun resetAutoChangeLearning() {
        autoNoiseEma = 0.0
        autoDynamicThreshold = if (translationMode == TranslationMode.VN_FAST) VN_CHANGE_THRESHOLD else DEFAULT_CHANGE_THRESHOLD
    }

    private fun learnAutoChangeThreshold(diff: Int) {
        val base = if (translationMode == TranslationMode.VN_FAST) VN_CHANGE_THRESHOLD else DEFAULT_CHANGE_THRESHOLD
        val clampedSample = min(diff, base * 4).toDouble()
        autoNoiseEma = if (autoNoiseEma == 0.0) clampedSample else (autoNoiseEma * 0.9) + (clampedSample * 0.1)
        autoDynamicThreshold = max(base, (autoNoiseEma * 2.5).toInt())
    }

    private fun isRegionChanged(previous: AutoRegionSignature, current: AutoRegionSignature): Boolean {
        val lumaDiff = kotlin.math.abs(previous.avgLuma - current.avgLuma)
        val edgeDiff = kotlin.math.abs(previous.edgeLuma - current.edgeLuma)
        val combinedDiff = lumaDiff + (edgeDiff * 2)
        learnAutoChangeThreshold(combinedDiff)
        return combinedDiff > autoDynamicThreshold
    }

    private fun captureRegionSignature(rect: Rect): AutoRegionSignature? {
        val projection = mediaProjection ?: run {
            if (resultCode == 0 || resultData == null) return null
            mediaProjectionManager?.getMediaProjection(resultCode, resultData!!)?.also { mediaProjection = it }
        } ?: return null

        val reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        val display = projection.createVirtualDisplay(
            "AutoTranslateMonitor",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null
        )

        return try {
            Thread.sleep(120)
            val image = reader.acquireLatestImage() ?: return null
            val plane = image.planes.firstOrNull() ?: run {
                image.close(); return null
            }
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val bufferWidth = rowStride / pixelStride
            val fullBitmap = Bitmap.createBitmap(bufferWidth, screenHeight, Bitmap.Config.ARGB_8888)
            fullBitmap.copyPixelsFromBuffer(plane.buffer)
            image.close()

            val safeRect = Rect(rect)
            val bitmapRect = Rect(0, 0, fullBitmap.width, fullBitmap.height)
            if (!safeRect.intersect(bitmapRect) || safeRect.width() < 2 || safeRect.height() < 2) {
                fullBitmap.recycle()
                return null
            }

            val cropped = Bitmap.createBitmap(fullBitmap, safeRect.left, safeRect.top, safeRect.width(), safeRect.height())
            fullBitmap.recycle()
            val sampleW = min(24, cropped.width)
            val sampleH = min(24, cropped.height)
            val scaled = Bitmap.createScaledBitmap(cropped, sampleW, sampleH, true)
            if (scaled != cropped) cropped.recycle()

            var lumaSum = 0L
            var edgeSum = 0L
            var sampleCount = 0
            val prevRow = IntArray(sampleW) { -1 }
            var y = 0
            while (y < sampleH) {
                var prevLumaInRow = -1
                var x = 0
                while (x < sampleW) {
                    val pixel = scaled.getPixel(x, y)
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    val luma = ((r * 3) + (g * 6) + b) / 10

                    lumaSum += luma
                    sampleCount++

                    if (prevLumaInRow >= 0) {
                        edgeSum += kotlin.math.abs(luma - prevLumaInRow)
                    }
                    if (prevRow[x] >= 0) {
                        edgeSum += kotlin.math.abs(luma - prevRow[x])
                    }

                    prevLumaInRow = luma
                    prevRow[x] = luma
                    x++
                }
                y++
            }
            scaled.recycle()
            if (sampleCount == 0) return null

            val avgLuma = (lumaSum / sampleCount).toInt()
            val avgEdge = (edgeSum / max(1, sampleCount * 2)).toInt()
            AutoRegionSignature(avgLuma = avgLuma, edgeLuma = avgEdge)
        } catch (e: Exception) {
            null
        } finally {
            display?.release()
            reader.close()
        }
    }

    private fun startCaptureWithCheck() {
        serviceScope.launch {
            val uid = auth.currentUser?.uid
            if (uid == null) {
                showCustomToast("로그인이 필요합니다.")
                isCaptureInProgress = false
                restoreOverlays()
                return@launch
            }

            // [변경] 등급별 잔액 체크
            if (translationMode == TranslationMode.DEFAULT && currentModelTier == "PRO") {
                val hasGold = try {
                    val snapshot = db.collection("users").document(uid).get().await()
                    (snapshot.getLong("gold_balance") ?: 0) >= 1
                } catch (e: Exception) { false }

                if (!hasGold) {
                    showCustomToast("골드가 부족합니다! 충전해주세요.")
                    isCaptureInProgress = false
                    restoreOverlays()
                    return@launch
                }
            } else if (translationMode == TranslationMode.DEFAULT && currentModelTier == "ADVANCED") {
                val hasSilver = try {
                    val snapshot = db.collection("users").document(uid).get().await()
                    (snapshot.getLong("current_balance") ?: 0) >= 1
                } catch (e: Exception) { false }

                if (!hasSilver) {
                    showCustomToast("실버가 부족합니다! 충전해주세요.")
                    isCaptureInProgress = false
                    restoreOverlays()
                    return@launch
                }
            }
            // STANDARD (무료) -> 패스

            delay(100)
            startCapture()
        }
    }

    private fun removeOverlayView() {
        resultOverlayView?.setImageDrawable(null)
        safeRemoveView(resultOverlayView)
        resultOverlayView = null
        releaseCurrentOverlayBitmap()
        restoreOverlays()
    }

    private fun releaseCurrentOverlayBitmap(recycleDelayMs: Long = 64L) {
        val oldBitmap = currentOverlayBitmap
        currentOverlayBitmap = null
        currentOverlayCanvas = null
        if (oldBitmap != null && !oldBitmap.isRecycled) {
            serviceScope.launch(Dispatchers.Main) {
                delay(recycleDelayMs)
                if (!oldBitmap.isRecycled) oldBitmap.recycle()
            }
        }
    }

    private suspend fun captureSignatureForAuto(rect: Rect): AutoRegionSignature? {
        val shouldTemporarilyHideOverlay =
            resultOverlayView?.visibility == View.VISIBLE && !isCaptureInProgress

        if (shouldTemporarilyHideOverlay) {
            withContext(Dispatchers.Main) {
                resultOverlayView?.visibility = View.INVISIBLE
            }
            // Wait one frame so overlay exclusion is reflected in captured surface.
            delay(16)
        }

        return try {
            withContext(Dispatchers.Default) { captureRegionSignature(rect) }
        } finally {
            if (shouldTemporarilyHideOverlay) {
                withContext(Dispatchers.Main) {
                    if (!isCaptureInProgress) {
                        resultOverlayView?.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun restoreOverlays() {
        serviceScope.launch {
            floatingButton.visibility = View.VISIBLE
            if (targetCaptureRect != null) indicatorOverlay?.visibility = View.VISIBLE
        }
    }

    private fun startCapture() {
        if (isCaptureInProgress) return
        isCaptureInProgress = true
        try {
            updateScreenMetrics()
            floatingButton.visibility = View.GONE
            resultOverlayView?.visibility = View.GONE
            captureEditorOverlayView?.visibility = View.GONE
            captureControlsOverlayView?.visibility = View.GONE
            indicatorOverlay?.visibility = View.GONE

            if (mediaProjection == null) {
                if (resultCode == 0 || resultData == null) {
                    showCustomToast("캡처 권한이 없습니다.")
                    isCaptureInProgress = false
                    restoreOverlays()
                    return
                }
                mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData!!)
            }
            imageReader?.close()
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)

            serviceScope.launch {
                delay(100)
                try {
                    virtualDisplay = mediaProjection?.createVirtualDisplay("ScreenCapture", screenWidth, screenHeight, screenDensity, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, null)
                    delay(300)
                    processCapturedImage()
                } catch (e: Exception) {
                    e.printStackTrace()
                    isCaptureInProgress = false
                    restoreOverlays()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            isCaptureInProgress = false
            restoreOverlays()
        }
    }

    private fun processCapturedImage() {
        serviceScope.launch(Dispatchers.Default) {
            try {
                val image = imageReader?.acquireLatestImage()
                if (image == null) {
                    withContext(Dispatchers.Main) {
                        Log.e(TAG, "Image capture failed")
                        isCaptureInProgress = false
                        restoreOverlays()
                    }
                    return@launch
                }

                val planes = image.planes; val buffer = planes[0].buffer; val pixelStride = planes[0].pixelStride; val rowStride = planes[0].rowStride; val bufferWidth = rowStride / pixelStride
                if (reusableBitmap == null || reusableBitmap?.width != bufferWidth || reusableBitmap?.height != screenHeight) {
                    reusableBitmap?.recycle()
                    reusableBitmap = Bitmap.createBitmap(bufferWidth, screenHeight, Bitmap.Config.ARGB_8888)
                }
                reusableBitmap!!.copyPixelsFromBuffer(buffer); image.close()

                var finalBitmap: Bitmap? = null
                val rect = targetCaptureRect

                if (rect != null) {
                    // [핵심 수정] 추가 보정 로직 제거: NO_LIMITS 사용 시 Raw Screen 좌표와 일치
                    // rect가 전체 화면 좌표 기준으로 저장되므로(selection에서 NO_LIMITS 사용),
                    // 그대로 잘라내면 됩니다.

                    val bitmapRect = Rect(0, 0, reusableBitmap!!.width, reusableBitmap!!.height)
                    val cropRect = Rect(rect)

                    // 교집합 영역만 자름 (화면 밖으로 나간 영역 자동 처리)
                    if (cropRect.intersect(bitmapRect)) {
                        finalBitmap = Bitmap.createBitmap(reusableBitmap!!, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())
                    } else {
                        Log.e(TAG, "Crop area is out of bounds! ")
                    }
                } else {
                    finalBitmap = Bitmap.createBitmap(reusableBitmap!!, 0, 0, screenWidth, screenHeight)
                }

                withContext(Dispatchers.Main) {
                    stopCaptureResource()
                    if (finalBitmap != null) runOCR(finalBitmap) else {
                        Log.e(TAG, "finalBitmap is null")
                        showCustomToast("캡처 영역 오류")
                        restoreOverlays()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) { restoreOverlays() }
            }
        }
    }

    private fun stopCaptureResource() { virtualDisplay?.release(); virtualDisplay = null; imageReader?.close(); imageReader = null }

    private fun runOCR(bitmap: Bitmap) {
        serviceScope.launch {
            val scaleFactor = 2.0f
            val shouldScale = bitmap.width * bitmap.height < 4000 * 4000
            val ocrBitmap = if (shouldScale) {
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * scaleFactor).toInt(), (bitmap.height * scaleFactor).toInt(), true)
            } else { bitmap }

            val recognizer = OcrManager.getRecognizer(sourceLang)
            val preNormal = OcrPreprocessor.preprocessForOcr(ocrBitmap, OcrPreprocessor.Mode.NORMAL)
            val preStrong = OcrPreprocessor.preprocessForOcr(ocrBitmap, OcrPreprocessor.Mode.STRONG)

            try {
                val firstText = recognizer.process(InputImage.fromBitmap(preNormal, 0)).await()
                var selectedText = firstText
                val firstScore = OcrPreprocessor.scoreTextQuality(firstText.text)

                if (firstScore < 0.62f) {
                    val secondText = recognizer.process(InputImage.fromBitmap(preStrong, 0)).await()
                    val secondScore = OcrPreprocessor.scoreTextQuality(secondText.text)
                    Log.d(TAG, "OCR retry score first=$firstScore second=$secondScore")
                    if (secondScore > firstScore) {
                        selectedText = secondText
                    }
                }

                val processedBlocks = MangaProcessor.processOCRResult(
                    selectedText,
                    sourceLang,
                    MangaProcessor.MergePolicy.CONSERVATIVE
                )
                val scaledBackBlocks = if (shouldScale) {
                    processedBlocks.map { block ->
                        block.copy(
                            boundingBox = scaleRect(block.boundingBox, 1 / scaleFactor),
                            lineBoxes = block.lineBoxes.map { scaleRect(it, 1 / scaleFactor) }
                        )
                    }
                } else { processedBlocks }

                translationQueue.clear()
                translationQueue.addAll(scaledBackBlocks.mapIndexed { idx, block -> block.copy(id = idx) })

                val detectedCharCount = translationQueue.sumOf { it.originalText.length }
                if (translationMode == TranslationMode.VN_FAST && detectedCharCount > VN_INPUT_CHAR_LIMIT) {
                    showCustomToast(getString(R.string.vn_input_limit_exceeded))
                    isCaptureInProgress = false
                    restoreOverlays()
                    if (isAutoMode) {
                        startAutoTranslateMonitoring()
                    }
                    return@launch
                }
                val maxCharCount = getMaxDetectedCharCount()
                if (detectedCharCount >= maxCharCount) {
                    showCustomToast(
                        "감지된 글자가 너무 많아 번역할 수 없습니다. (감지: ${detectedCharCount}자 / 제한: ${maxCharCount}자)"
                    )
                    isCaptureInProgress = false
                    restoreOverlays()
                } else if (translationQueue.isNotEmpty()) {
                    processTranslationWithAnimation(bitmap)
                } else {
                    showCustomToast("텍스트를 찾지 못했습니다.")
                    isCaptureInProgress = false
                    restoreOverlays()
                }
            } catch (e: Exception) {
                showCustomToast("OCR 실패")
                isCaptureInProgress = false
                restoreOverlays()
            } finally {
                if (!preNormal.isRecycled) preNormal.recycle()
                if (!preStrong.isRecycled) preStrong.recycle()
                if (ocrBitmap != bitmap && !ocrBitmap.isRecycled) ocrBitmap.recycle()
                recognizer.close()
            }
        }
    }

    private fun processTranslationWithAnimation(originalBitmap: Bitmap) {
        val previousOverlayBitmap = currentOverlayBitmap
        currentOverlayBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
        currentOverlayCanvas = Canvas(currentOverlayBitmap!!)
        showOverlayView(currentOverlayBitmap!!)
        if (previousOverlayBitmap != null &&
            previousOverlayBitmap !== currentOverlayBitmap &&
            !previousOverlayBitmap.isRecycled
        ) {
            serviceScope.launch(Dispatchers.Main) {
                delay(32)
                if (!previousOverlayBitmap.isRecycled) previousOverlayBitmap.recycle()
            }
        }

        serviceScope.launch {
            val translationDeferred = async(Dispatchers.IO) {
                try {
                    if (translationMode == TranslationMode.VN_FAST) {
                        performVnFastTranslation(translationQueue)
                        true
                    } else if (currentModelTier == "STANDARD") {
                        // Local ML Kit Translation
                        performLocalTranslation(translationQueue, sourceLang)
                        true
                    } else {
                        // Server Translation
                        TranslationRepository.translate(
                            blocks = translationQueue,
                            targetLang = targetLang,
                            imageCount = 1,
                            serviceType = "SCREEN",
                            modelTier = currentModelTier // [추가]
                        )
                        true
                    }
                } catch (e: Exception) {
                    if (translationMode == TranslationMode.VN_FAST) {
                        if (e is FirebaseFunctionsException &&
                            e.code == FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED) {
                            showCustomToast(getString(R.string.vn_credit_insufficient))
                        } else {
                            showCustomToast(getString(R.string.vn_translation_error))
                        }
                    }
                    false
                }
            }

            val delayTime = if (translationQueue.size > 10) 50L else 150L
            for (block in translationQueue) {
                MangaProcessor.drawMasking(currentOverlayCanvas!!, originalBitmap, listOf(block))
                resultOverlayView?.invalidate()
                delay(delayTime)
            }

            if (translationDeferred.isActive) {
                showLoadingOverlay()
                updateLoadingText("번역중...")
            }
            val isSuccess = translationDeferred.await()
            hideLoadingOverlay()

            if (isSuccess) updateOverlayWithText() else {
                isCaptureInProgress = false
                if (translationMode != TranslationMode.VN_FAST) {
                    showCustomToast("번역 중 오류가 발생했습니다.")
                }
                restoreOverlays()
            }
        }
    }

    private suspend fun performVnFastTranslation(blocks: List<MangaBlock>) {
        val requests = blocks.map {
            VnFastTranslationRepository.VnFastRequest(
                id = it.id,
                text = it.originalText
            )
        }
        val response = VnFastTranslationRepository.translateVnFast(
            requests = requests,
            targetLang = vnTargetLangCode,
            sourceLang = vnSourceLangCode
        )
        val translatedById = response.results.associateBy { it.id }
        blocks.forEach { block ->
            block.translatedText = translatedById[block.id.toString()]?.text ?: ""
        }
    }

    // [New] ML Kit 로컬 번역 실행 (STANDARD tier)
    private suspend fun performLocalTranslation(blocks: List<MangaBlock>, sourceLang: String) {
        val srcCode = mapLangCode(sourceLang)
        val tgtCode = mapLangCode(targetLang)
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(srcCode)
            .setTargetLanguage(tgtCode)
            .build()
        val translator = Translation.getClient(options)

        try {
            // 모델 다운로드 확인
            val conditions = DownloadConditions.Builder().requireWifi().build()
            translator.downloadModelIfNeeded(conditions).await()

            blocks.forEach { block ->
                try {
                    val result = translator.translate(block.originalText).await()
                    block.translatedText = result
                } catch (e: Exception) {
                    block.translatedText = "번역 실패"
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            translator.close()
        }
    }

    private fun mapLangCode(lang: String): String {
        return when(lang) {
            "Japanese" -> TranslateLanguage.JAPANESE
            "English" -> TranslateLanguage.ENGLISH
            "Chinese" -> TranslateLanguage.CHINESE
            "Korean" -> TranslateLanguage.KOREAN
            else -> TranslateLanguage.JAPANESE
        }
    }

    private fun scaleRect(rect: Rect, factor: Float): Rect {
        return Rect((rect.left * factor).toInt(), (rect.top * factor).toInt(), (rect.right * factor).toInt(), (rect.bottom * factor).toInt())
    }

    private fun updateOverlayWithText() {
        if (currentOverlayCanvas == null) return
        MangaProcessor.drawTranslation(currentOverlayCanvas!!, translationQueue, sourceLang)
        resultOverlayView?.invalidate()
        isCaptureInProgress = false

        if (isAutoMode) {
            // [변경] 자동 화면번역: 1차 번역 완료 후 자동 모니터링 시작
            startAutoTranslateMonitoring()
        } else if (!isAutoTranslateEnabled) {
            floatingButton.visibility = View.VISIBLE
            if (targetCaptureRect != null) indicatorOverlay?.visibility = View.VISIBLE
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel("SCREEN_TRANS_CHANNEL", "Screen Translation", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, "SCREEN_TRANS_CHANNEL") else Notification.Builder(this)
        return builder.setContentTitle("화면 번역 실행 중")
            .setContentText("모델: $currentModelTier") // [추가]
            .setSmallIcon(android.R.drawable.ic_menu_search).build()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        reusableBitmap?.recycle(); reusableBitmap = null
        removeOverlayView()
        safeRemoveView(floatingButton)
        removeIndicatorOverlay()
        hideLoadingOverlay()
        stopAutoTranslateMonitoring()
        removeCaptureAreaOverlay(keepFloatingButton = false)
        safeRemoveView(currentToastView)
    }
}

