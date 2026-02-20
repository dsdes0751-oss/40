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
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
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
        const val ACTION_UPDATE_MODEL = "com.tuna.proj_01.UPDATE_MODEL"
        const val ACTION_UPDATE_TARGET_LANG = "com.tuna.proj_01.UPDATE_TARGET_LANG"
        const val ACTION_SERVICE_STOPPED = "com.tuna.proj_01.SERVICE_STOPPED"
        const val EXTRA_TRANSLATION_MODE = "extra_translation_mode"
        const val MODE_VN_FAST = "VN_FAST"
        const val EXTRA_VN_TARGET_LANG = "extra_vn_target_lang"
        const val EXTRA_VN_SOURCE_LANG = "extra_vn_source_lang"
        private const val TAG = "ScreenTranslation"
        private const val VN_INPUT_CHAR_LIMIT = 90
        private const val DEFAULT_ANALYZE_HZ = 8L
        private const val VN_ANALYZE_HZ = 12L
        private const val DEFAULT_STABLE_FRAMES = 4
        private const val VN_STABLE_FRAMES = 3
        private const val DEFAULT_HASH_DISTANCE_THRESHOLD = 8
        private const val VN_HASH_DISTANCE_THRESHOLD = 6
        private const val MONITOR_DIVISOR = 3
        private const val MONITOR_MAX_SIDE = 480
        // [癰궰野? MAX_DETECTED_CHAR_COUNT ?怨몃땾 ??볤탢, getMaxDetectedCharCount()嚥???덉읅 ?怨몄뒠
    }

    private enum class TranslationMode {
        DEFAULT,
        VN_FAST
    }

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
    private var currentModelTier: String = "ADVANCED" // [?곕떽?] 疫꿸퀡??첎?ADVANCED
    private var translationMode: TranslationMode = TranslationMode.DEFAULT
    private var vnTargetLangCode: String = "KO"
    private var vnSourceLangCode: String? = null

    // [癰궰野? ?????紐꾨선癰?筌ㅼ뮆? 疫꼲??????쀫립 (?怨몃선: 425, 疫꿸퀡?? 250)
    private fun getMaxDetectedCharCount(): Int {
        return if (targetLang == "English") 425 else 250
    }
    private var targetCaptureRect: Rect? = null

    private var currentOverlayBitmap: Bitmap? = null
    private var currentOverlayCanvas: Canvas? = null

    private var isRunning = false
    private var isCaptureInProgress = false
    private var isAutoTranslateEnabled = false
    private var autoTranslateMonitor: AutoTranslateMonitor? = null
    private var isAutoTriggerPending = false
    private var isAutoMode = false  // [?곕떽?] ?癒?짗 ?遺얇늺甕곕뜆肉?筌뤴뫀諭????

    private var lastClickTime: Long = 0
    private val doubleClickDelay = 300L
    private val TOUCH_SLOP = 30f
    private var isLongPressJob: Job? = null
    private var singleClickJob: Job? = null
    private val debugLogAtMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

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

    // ?遺얇늺 ??곴맒???類ｋ궖??筌ㅼ뮇?듿첎誘れ몵嚥?揶쏄퉮???롫뮉 ??λ땾
    private fun updateScreenMetrics() {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenDensity = metrics.densityDpi
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        captureEditorOverlayView?.setScreenSize(screenWidth, screenHeight)
        Log.d(TAG, "Screen metrics updated: ${screenWidth}x${screenHeight}")
    }

    private fun logDebugThrottled(key: String, intervalMs: Long = 2000L, message: String) {
        val now = SystemClock.elapsedRealtime()
        val prev = debugLogAtMs[key]
        if (prev == null || now - prev >= intervalMs) {
            debugLogAtMs[key] = now
            Log.d(TAG, message)
        }
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
                    showCustomToast("踰덉뿭 ?몄뼱 蹂寃? $sourceLang")
                }
                return START_NOT_STICKY
            }
            ACTION_UPDATE_MODEL -> {
                val newModel = intent.getStringExtra("modelTier")
                if (newModel != null) {
                    currentModelTier = newModel
                    val modelName = when(currentModelTier) {
                        "STANDARD" -> "?쇰컲 踰덉뿭 (臾대즺)"
                        "ADVANCED" -> "怨좉툒 踰덉뿭 (?ㅻ쾭)"
                        "PRO" -> "?꾨줈 踰덉뿭 (怨⑤뱶)"
                        else -> "?????놁쓬"
                    }
                    showCustomToast("紐⑤뜽 蹂寃? $modelName")
                }
                return START_NOT_STICKY
            }
            ACTION_UPDATE_TARGET_LANG -> {
                val newTargetLang = intent.getStringExtra("targetLang")
                if (newTargetLang != null) {
                    targetLang = newTargetLang
                    showCustomToast("踰덉뿭 ????몄뼱 蹂寃? $targetLang")
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
            // ?λ뜃由???쎈뻬 ??筌뤴뫀????쇱젟揶???뤿뻿
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

            // [?곕떽?] ?癒?짗 ?遺얇늺甕곕뜆肉?筌뤴뫀諭???쇱젟
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

            showCustomToast("?붾㈃ 踰덉뿭 ?쒕퉬?ㅺ? 醫낅즺?섏뿀?듬땲??")

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
                text = "?띿뒪??遺꾩꽍 以?.."
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
        // [??륁젟] ??쎈늄???얜챷????욧퍙: FLAG_LAYOUT_NO_LIMITS ?????(?ル슦紐????뵬)
        val params = WindowManager.LayoutParams(
            bitmap.width,
            bitmap.height,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or // ???쎾첎?: ?遺얇늺 ?袁⑷퍥???ル슦紐?疫꿸퀣???곗쨮 ????                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        )

        // [餓λ쵐?? ?뚮９釉???怨몃열繹먮슣? ??살쒔??됱뵠????뽯뻻??롫즲嚥???쇱젟
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
                // [??륁젟] NO_LIMITS ???삋域??怨몄뒠
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

            btnConfirm.text = "?곸뿭 ?ㅼ젙 ?꾨즺"
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
                        showCustomToast("?곸뿭??怨좎젙?섏뿀?듬땲?? ?뗫낫湲곕? ?뚮윭 踰덉뿭?섏꽭??")
                    }
                } else {
                    showCustomToast("?곸뿭?????ш쾶 吏?뺥빐二쇱꽭??")
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
            showCustomToast("?ㅻ쾭?덉씠 ?ㅽ뻾 ?ㅻ쪟")
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
            showCustomToast("沅뚰븳 ?ㅻ쪟: ?깆쓣 ?ㅼ떆 ?ㅽ뻾?댁＜?몄슂")
            return
        }
        loadCaptureSettings()

        if (targetCaptureRect != null) {
            showIndicatorOverlay(targetCaptureRect!!)
            if (isAutoMode) {
                // [癰궰野? ?癒?짗 ?遺얇늺甕곕뜆肉?筌뤴뫀諭? ?癒?짗 甕곕뜆肉???쎈뻬
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

    private fun startAutoTranslateMonitoring(
        triggerInitialCapture: Boolean = true,
        showToast: Boolean = true
    ) {
        val rect = targetCaptureRect
        if (rect == null) {
            Log.w(TAG, "Auto monitor start blocked: targetCaptureRect is null")
            showCustomToast("癒쇱? 踰덉뿭 ?곸뿭??吏?뺥빐二쇱꽭??")
            return
        }

        Log.d(TAG, "Auto monitor start requested: rect=$rect, triggerInitialCapture=$triggerInitialCapture, mode=$translationMode")
        autoTranslateMonitor?.stop()
        isAutoTranslateEnabled = true
        isAutoTriggerPending = false

        val monitor = AutoTranslateMonitor(
            triggerInitialCapture = triggerInitialCapture,
            onStableContent = { triggerTranslationFromAuto() }
        )
        if (!monitor.start(Rect(rect))) {
            isAutoTranslateEnabled = false
            autoTranslateMonitor = null
            Log.e(TAG, "Auto monitor start failed")
            showCustomToast("?먮룞踰덉뿭 媛먯떆 ?쒖옉 ?ㅽ뙣")
            return
        }

        autoTranslateMonitor = monitor
        if (showToast) {
            showCustomToast("자동번역 시작: 영역 변화 감지 중")
        }
    }

    private fun stopAutoTranslateMonitoring(showToast: Boolean = false) {
        Log.d(TAG, "Auto monitor stop requested")
        isAutoTranslateEnabled = false
        isAutoTriggerPending = false
        autoTranslateMonitor?.stop()
        autoTranslateMonitor = null
        if (showToast) showCustomToast("?먮룞踰덉뿭??醫낅즺?섏뿀?듬땲??")
    }

    private fun triggerTranslationFromAuto() {
        if (!isAutoTranslateEnabled) {
            logDebugThrottled("auto_trigger_blocked_disabled", message = "Auto trigger blocked: monitor disabled")
            return
        }
        if (isCaptureInProgress) {
            logDebugThrottled("auto_trigger_blocked_capturing", message = "Auto trigger blocked: capture in progress")
            return
        }
        if (isAutoTriggerPending) {
            logDebugThrottled("auto_trigger_blocked_pending", message = "Auto trigger blocked: trigger already pending")
            return
        }

        isAutoTriggerPending = true
        Log.d(TAG, "Auto trigger accepted: start capture")
        removeOverlayView()
        floatingButton.visibility = View.VISIBLE
        startCaptureWithCheck()
    }

    private fun ensureMediaProjection(): MediaProjection? {
        val existing = mediaProjection
        if (existing != null) return existing
        if (resultCode == 0 || resultData == null) return null
        return mediaProjectionManager?.getMediaProjection(resultCode, resultData!!)?.also {
            mediaProjection = it
        }
    }

    private fun getAnalyzeIntervalMs(): Long {
        val hz = if (translationMode == TranslationMode.VN_FAST) VN_ANALYZE_HZ else DEFAULT_ANALYZE_HZ
        return (1000L / hz).coerceAtLeast(1L)
    }

    private fun getStableFramesRequired(): Int {
        return if (translationMode == TranslationMode.VN_FAST) VN_STABLE_FRAMES else DEFAULT_STABLE_FRAMES
    }

    private fun getHashDistanceThreshold(): Int {
        return if (translationMode == TranslationMode.VN_FAST) VN_HASH_DISTANCE_THRESHOLD else DEFAULT_HASH_DISTANCE_THRESHOLD
    }

    private fun calculateMonitorSize(fullWidth: Int, fullHeight: Int): Pair<Int, Int> {
        var monitorWidth = (fullWidth / MONITOR_DIVISOR).coerceAtLeast(64)
        var monitorHeight = (fullHeight / MONITOR_DIVISOR).coerceAtLeast(64)
        val maxSide = max(monitorWidth, monitorHeight)
        if (maxSide > MONITOR_MAX_SIDE) {
            monitorWidth = (monitorWidth * MONITOR_MAX_SIDE) / maxSide
            monitorHeight = (monitorHeight * MONITOR_MAX_SIDE) / maxSide
        }
        return Pair(monitorWidth.coerceAtLeast(32), monitorHeight.coerceAtLeast(32))
    }

    private fun mapRectToMonitor(rect: Rect, monitorWidth: Int, monitorHeight: Int): Rect? {
        if (screenWidth <= 0 || screenHeight <= 0) return null
        val left = ((rect.left.toLong() * monitorWidth) / screenWidth).toInt().coerceIn(0, monitorWidth - 1)
        val top = ((rect.top.toLong() * monitorHeight) / screenHeight).toInt().coerceIn(0, monitorHeight - 1)
        val rightRaw = ((rect.right.toLong() * monitorWidth) / screenWidth).toInt()
        val bottomRaw = ((rect.bottom.toLong() * monitorHeight) / screenHeight).toInt()
        val right = rightRaw.coerceIn(left + 1, monitorWidth)
        val bottom = bottomRaw.coerceIn(top + 1, monitorHeight)
        if (right - left < 2 || bottom - top < 2) return null
        return Rect(left, top, right, bottom)
    }

    private inner class AutoTranslateMonitor(
        private val triggerInitialCapture: Boolean,
        private val onStableContent: () -> Unit
    ) {
        private var monitorThread: HandlerThread? = null
        private var monitorReader: ImageReader? = null
        private var monitorDisplay: VirtualDisplay? = null
        private var monitorRect: Rect? = null

        private var lastAnalyzeAtMs = 0L
        private var lastHash: Long? = null
        private var hasChange = false
        private var stableCount = 0
        private var initialPending = triggerInitialCapture
        private val dHashSamples = IntArray(9 * 8)

        private val analyzeIntervalMs = getAnalyzeIntervalMs()
        private val stableFramesRequired = getStableFramesRequired()
        private val distanceThreshold = getHashDistanceThreshold()

        fun start(targetRect: Rect): Boolean {
            stop()

            val projection = ensureMediaProjection() ?: run {
                Log.e(TAG, "Auto monitor start failed: mediaProjection is null")
                return false
            }
            updateScreenMetrics()

            val (monitorWidth, monitorHeight) = calculateMonitorSize(screenWidth, screenHeight)
            val scaledRect = mapRectToMonitor(targetRect, monitorWidth, monitorHeight) ?: run {
                Log.e(TAG, "Auto monitor start failed: scaledRect is null, targetRect=$targetRect, monitor=${monitorWidth}x$monitorHeight")
                return false
            }

            val thread = HandlerThread("AutoTranslateMonitorThread")
            thread.start()
            val handler = Handler(thread.looper)

            return try {
                val reader = ImageReader.newInstance(monitorWidth, monitorHeight, PixelFormat.RGBA_8888, 2)
                val display = projection.createVirtualDisplay(
                    "AutoTranslateMonitor",
                    monitorWidth,
                    monitorHeight,
                    screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    handler
                )

                monitorThread = thread
                monitorReader = reader
                monitorDisplay = display
                monitorRect = scaledRect
                reader.setOnImageAvailableListener({ onImageAvailable(it) }, handler)
                Log.d(TAG, "Auto monitor started: monitor=${monitorWidth}x$monitorHeight, roi=$scaledRect, interval=${analyzeIntervalMs}ms, stableFrames=$stableFramesRequired, threshold=$distanceThreshold")
                true
            } catch (e: Exception) {
                try { thread.quitSafely() } catch (_: Exception) {}
                Log.e(TAG, "Auto monitor start exception", e)
                false
            }
        }

        fun stop() {
            try {
                monitorReader?.setOnImageAvailableListener(null, null)
            } catch (_: Exception) {}
            try {
                monitorDisplay?.release()
            } catch (_: Exception) {}
            try {
                monitorReader?.close()
            } catch (_: Exception) {}
            try {
                monitorThread?.quitSafely()
            } catch (_: Exception) {}

            monitorDisplay = null
            monitorReader = null
            monitorThread = null
            monitorRect = null
            lastAnalyzeAtMs = 0L
            lastHash = null
            hasChange = false
            stableCount = 0
            initialPending = triggerInitialCapture
            Log.d(TAG, "Auto monitor stopped")
        }

        private fun onImageAvailable(reader: ImageReader) {
            val image = try {
                reader.acquireLatestImage()
            } catch (_: Exception) {
                null
            } ?: return

            try {
                if (!isAutoTranslateEnabled) {
                    logDebugThrottled("auto_skip_disabled", message = "Auto monitor skip frame: monitor disabled")
                    return
                }
                if (isCaptureInProgress) {
                    logDebugThrottled("auto_skip_capture", message = "Auto monitor skip frame: capture in progress")
                    return
                }
                if (isAutoTriggerPending) {
                    logDebugThrottled("auto_skip_pending", message = "Auto monitor skip frame: trigger pending")
                    return
                }

                val now = SystemClock.elapsedRealtime()
                if (now - lastAnalyzeAtMs < analyzeIntervalMs) {
                    return
                }
                lastAnalyzeAtMs = now

                val rect = monitorRect ?: return
                val hash = computeDHash(image, rect) ?: run {
                    logDebugThrottled("auto_hash_null", message = "Auto monitor hash failed: image=${image.width}x${image.height}, roi=$rect")
                    return
                }
                val previousHash = lastHash

                if (previousHash == null) {
                    lastHash = hash
                    if (initialPending) {
                        initialPending = false
                        serviceScope.launch(Dispatchers.Main) {
                            if (isAutoTranslateEnabled && !isCaptureInProgress && !isAutoTriggerPending) {
                                Log.d(TAG, "Auto monitor: initial trigger")
                                onStableContent()
                            }
                        }
                    }
                    return
                }

                val distance = java.lang.Long.bitCount(previousHash xor hash)
                lastHash = hash

                if (distance >= distanceThreshold) {
                    if (!hasChange) {
                        Log.d(TAG, "Auto monitor: changed(distance=$distance)")
                    }
                    hasChange = true
                    stableCount = 0
                    return
                }

                if (!hasChange) return

                stableCount += 1
                if (stableCount >= stableFramesRequired) {
                    hasChange = false
                    stableCount = 0
                    Log.d(TAG, "Auto monitor: stable(stableCount=$stableFramesRequired, distance=$distance)")
                    serviceScope.launch(Dispatchers.Main) {
                        if (isAutoTranslateEnabled && !isCaptureInProgress && !isAutoTriggerPending) {
                            onStableContent()
                        }
                    }
                }
            } finally {
                image.close()
            }
        }

        private fun computeDHash(image: Image, roiRect: Rect): Long? {
            val plane = image.planes.firstOrNull() ?: return null
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            if (pixelStride < 4 || image.width < 2 || image.height < 2) return null

            val left = roiRect.left.coerceIn(0, image.width - 2)
            val top = roiRect.top.coerceIn(0, image.height - 2)
            val right = roiRect.right.coerceIn(left + 2, image.width)
            val bottom = roiRect.bottom.coerceIn(top + 2, image.height)
            val roiWidth = right - left
            val roiHeight = bottom - top
            if (roiWidth < 2 || roiHeight < 2) return null

            val buffer = plane.buffer
            val bufferLimit = buffer.limit()
            var sampleIndex = 0
            val widthMinusOne = roiWidth - 1
            val heightMinusOne = roiHeight - 1

            // 9x8 域밸챶???곷뮞?냈????묐탣??筌욊낯????뚮선 64??쑵??dHash???④쑴沅??뺣뼄.
            for (yStep in 0 until 8) {
                val sampleY = top + ((yStep * heightMinusOne) / 7)
                for (xStep in 0 until 9) {
                    val sampleX = left + ((xStep * widthMinusOne) / 8)
                    val offset = (sampleY * rowStride) + (sampleX * pixelStride)
                    if (offset < 0 || offset + 2 >= bufferLimit) return null

                    val r = buffer.get(offset).toInt() and 0xFF
                    val g = buffer.get(offset + 1).toInt() and 0xFF
                    val b = buffer.get(offset + 2).toInt() and 0xFF
                    dHashSamples[sampleIndex] = ((r * 3) + (g * 6) + b) / 10
                    sampleIndex += 1
                }
            }

            var hash = 0L
            var bitIndex = 0
            for (row in 0 until 8) {
                val base = row * 9
                for (col in 0 until 8) {
                    if (dHashSamples[base + col + 1] >= dHashSamples[base + col]) {
                        hash = hash or (1L shl bitIndex)
                    }
                    bitIndex += 1
                }
            }
            return hash
        }
    }

    private fun startCaptureWithCheck() {
        serviceScope.launch {
            val uid = auth.currentUser?.uid
            if (uid == null) {
                Log.w(TAG, "Capture blocked: user is not logged in")
                showCustomToast("濡쒓렇?몄씠 ?꾩슂?⑸땲??")
                isAutoTriggerPending = false
                isCaptureInProgress = false
                restoreOverlays()
                return@launch
            }

            // 잔액 체크
            if (translationMode == TranslationMode.DEFAULT && currentModelTier == "PRO") {
                val hasGold = try {
                    val snapshot = db.collection("users").document(uid).get().await()
                    (snapshot.getLong("gold_balance") ?: 0) >= 1
                } catch (e: Exception) { false }

                if (!hasGold) {
                    Log.w(TAG, "Capture blocked: insufficient gold for PRO tier")
                    showCustomToast("怨⑤뱶媛 遺議깊빀?덈떎! 異⑹쟾?댁＜?몄슂.")
                    isAutoTriggerPending = false
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
                    Log.w(TAG, "Capture blocked: insufficient silver for ADVANCED tier")
                    showCustomToast("?ㅻ쾭媛 遺議깊빀?덈떎! 異⑹쟾?댁＜?몄슂.")
                    isAutoTriggerPending = false
                    isCaptureInProgress = false
                    restoreOverlays()
                    return@launch
                }
            }
            // STANDARD (?얜?利? -> ??λ뮞

            delay(100)
            Log.d(TAG, "Capture check passed: startCapture()")
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

    private fun restoreOverlays() {
        serviceScope.launch {
            floatingButton.visibility = View.VISIBLE
            if (targetCaptureRect != null) indicatorOverlay?.visibility = View.VISIBLE
        }
    }

    private fun startCapture() {
        if (isCaptureInProgress) {
            isAutoTriggerPending = false
            return
        }
        isAutoTriggerPending = false
        isCaptureInProgress = true
        try {
            updateScreenMetrics()
            floatingButton.visibility = View.GONE
            resultOverlayView?.visibility = View.GONE
            captureEditorOverlayView?.visibility = View.GONE
            captureControlsOverlayView?.visibility = View.GONE
            indicatorOverlay?.visibility = View.GONE

            val projection = ensureMediaProjection()
            if (projection == null) {
                Log.e(TAG, "Capture blocked: mediaProjection is null")
                showCustomToast("罹≪쿂 沅뚰븳???놁뒿?덈떎.")
                isCaptureInProgress = false
                restoreOverlays()
                return
            }
            imageReader?.close()
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)

            serviceScope.launch {
                delay(100)
                try {
                    Log.d(TAG, "Capture start: screen=${screenWidth}x$screenHeight")
                    virtualDisplay = projection.createVirtualDisplay("ScreenCapture", screenWidth, screenHeight, screenDensity, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, null)
                    delay(300)
                    processCapturedImage()
                } catch (e: Exception) {
                    Log.e(TAG, "Capture failed while creating virtual display", e)
                    e.printStackTrace()
                    isCaptureInProgress = false
                    restoreOverlays()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Capture start exception", e)
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
                    // [???뼎 ??륁젟] ?곕떽? 癰귣똻??嚥≪뮇彛???볤탢: NO_LIMITS ??????Raw Screen ?ル슦紐?? ??깊뒄
                    // rect揶쎛 ?袁⑷퍥 ?遺얇늺 ?ル슦紐?疫꿸퀣???곗쨮 ???貫由븃첋?嚥?selection?癒?퐣 NO_LIMITS ????,
                    // 域밸챶?嚥???롮뵬??????몃빍??

                    val bitmapRect = Rect(0, 0, reusableBitmap!!.width, reusableBitmap!!.height)
                    val cropRect = Rect(rect)

                    // ?대Ŋ彛???怨몃열筌??癒?カ (?遺얇늺 獄쏅쉼?앮에???띿퍢 ?怨몃열 ?癒?짗 筌ｌ꼶??
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
                        showCustomToast("罹≪쿂 ?곸뿭 ?ㅻ쪟")
                        restoreOverlays()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "processCapturedImage failed", e)
                e.printStackTrace()
                withContext(Dispatchers.Main) { restoreOverlays() }
            }
        }
    }

    private fun stopCaptureResource() { virtualDisplay?.release(); virtualDisplay = null; imageReader?.close(); imageReader = null }

    private fun runOCR(bitmap: Bitmap) {
        serviceScope.launch {
            Log.d(TAG, "OCR start: bitmap=${bitmap.width}x${bitmap.height}, sourceLang=$sourceLang, mode=$translationMode, model=$currentModelTier")
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
                Log.d(TAG, "OCR result: blocks=${translationQueue.size}, detectedChars=$detectedCharCount")
                if (translationMode == TranslationMode.VN_FAST && detectedCharCount > VN_INPUT_CHAR_LIMIT) {
                    Log.w(TAG, "OCR blocked: VN input limit exceeded ($detectedCharCount > $VN_INPUT_CHAR_LIMIT)")
                    showCustomToast(getString(R.string.vn_input_limit_exceeded))
                    isCaptureInProgress = false
                    restoreOverlays()
                    if (isAutoMode) {
                        startAutoTranslateMonitoring(triggerInitialCapture = false, showToast = false)
                    }
                    return@launch
                }
                val maxCharCount = getMaxDetectedCharCount()
                if (detectedCharCount >= maxCharCount) {
                    Log.w(TAG, "OCR blocked: max char exceeded ($detectedCharCount >= $maxCharCount)")
                    showCustomToast(
                        "媛먯???湲?먭? ?덈Т 留롮븘 踰덉뿭?????놁뒿?덈떎. (媛먯?: ${detectedCharCount}??/ ?쒗븳: ${maxCharCount}??"
                    )
                    isCaptureInProgress = false
                    restoreOverlays()
                } else if (translationQueue.isNotEmpty()) {
                    Log.d(TAG, "OCR pass: start translation animation")
                    processTranslationWithAnimation(bitmap)
                } else {
                    Log.w(TAG, "OCR produced no blocks")
                    showCustomToast("?띿뒪?몃? 李얠? 紐삵뻽?듬땲??")
                    isCaptureInProgress = false
                    restoreOverlays()
                }
            } catch (e: Exception) {
                Log.e(TAG, "OCR failed", e)
                showCustomToast("OCR ?ㅽ뙣")
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
                        Log.d(TAG, "Translation start: VN_FAST blocks=${translationQueue.size}")
                        performVnFastTranslation(translationQueue)
                        true
                    } else if (currentModelTier == "STANDARD") {
                        // Local ML Kit Translation
                        Log.d(TAG, "Translation start: LOCAL blocks=${translationQueue.size}")
                        performLocalTranslation(translationQueue, sourceLang)
                        true
                    } else {
                        // Server Translation
                        Log.d(TAG, "Translation start: SERVER blocks=${translationQueue.size}, model=$currentModelTier, target=$targetLang")
                        TranslationRepository.translate(
                            blocks = translationQueue,
                            targetLang = targetLang,
                            imageCount = 1,
                            serviceType = if (isAutoMode) "SCREEN_AUTO" else "SCREEN_MANUAL",
                            modelTier = currentModelTier // [?곕떽?]
                        )
                        true
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Translation failed", e)
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
                updateLoadingText("踰덉뿭以?..")
            }
            val isSuccess = translationDeferred.await()
            hideLoadingOverlay()

            if (isSuccess) {
                Log.d(TAG, "Translation success")
                updateOverlayWithText()
            } else {
                Log.w(TAG, "Translation result: failed")
                isCaptureInProgress = false
                if (translationMode != TranslationMode.VN_FAST) {
                    showCustomToast("踰덉뿭 以??ㅻ쪟媛 諛쒖깮?덉뒿?덈떎.")
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

    // [New] ML Kit 嚥≪뮇類?甕곕뜆肉???쎈뻬 (STANDARD tier)
    private suspend fun performLocalTranslation(blocks: List<MangaBlock>, sourceLang: String) {
        val srcCode = mapLangCode(sourceLang)
        val tgtCode = mapLangCode(targetLang)
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(srcCode)
            .setTargetLanguage(tgtCode)
            .build()
        val translator = Translation.getClient(options)

        try {
            // 筌뤴뫀????쇱뒲嚥≪뮆諭??類ㅼ뵥
            val conditions = DownloadConditions.Builder().requireWifi().build()
            translator.downloadModelIfNeeded(conditions).await()

            blocks.forEach { block ->
                try {
                    val result = translator.translate(block.originalText).await()
                    block.translatedText = result
                } catch (e: Exception) {
                    block.translatedText = "踰덉뿭 ?ㅽ뙣"
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
            // [癰궰野? ?癒?짗 ?遺얇늺甕곕뜆肉? 1筌?甕곕뜆肉??袁⑥┷ ???癒?짗 筌뤴뫀??怨뺤춦 ??뽰삂
            startAutoTranslateMonitoring(triggerInitialCapture = false, showToast = false)
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
            .setContentText("紐⑤뜽: $currentModelTier")
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


