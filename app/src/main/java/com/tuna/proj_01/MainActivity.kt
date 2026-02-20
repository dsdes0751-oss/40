package com.tuna.proj_01

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
        // Matches app/google-services.json (OAuth web client, type 3)
        private const val GOOGLE_WEB_CLIENT_ID = "691923720143-m2dbnoal2cbc8vn6piu7c7gsi55p7lid.apps.googleusercontent.com"
    }

    // [추가] 화면 번역 최초 사용 여부 키
    private val KEY_HAS_USED_SCREEN_TRANS = "has_used_screen_trans"

    private val viewModel: MainViewModel by viewModels()

    // Views
    private lateinit var cardLogin: CardView
    private lateinit var btnLoginContainer: LinearLayout

    private lateinit var cardSilverInfo: CardView
    private lateinit var tvSilverBalance: TextView
    private lateinit var tvGoldBalance: TextView // [추가]
    private lateinit var btnStore: LinearLayout

    private lateinit var btnSettings: ImageView

    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnStopWork: Button

    // Action Cards
    private lateinit var btnSelectOverlay: View
    private lateinit var btnScreenTransOverlay: View
    private lateinit var btnAutoScreenTrans: View  // [추가] 자동 화면번역 버튼
    private lateinit var btnNovelTranslation: View
    private lateinit var btnVnGameTranslation: View

    // UI Elements for updates
    private lateinit var tvTransTitle: TextView
    private lateinit var ivTransIcon: ImageView
    private lateinit var ivAutoTransIcon: ImageView  // [추가] 자동 번역 아이콘
    private lateinit var tvAutoTransTitle: TextView   // [추가] 자동 번역 타이틀

    private var pendingAutoMode = false  // [추가] 자동 모드 대기 플래그

    private lateinit var spinnerLang: Spinner
    private lateinit var spinnerTargetLang: Spinner
    private lateinit var btnModelSelect: TextView // [추가]
    private lateinit var cardModelSelect: CardView // [추가]

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var auth: FirebaseAuth

    private lateinit var billingManager: BillingManager

    private var isServiceRunning = false
    private var originalIconColor: ColorStateList? = null

    // 현재 선택된 모델 (기본값: ADVANCED)
    private var currentModelTier = "ADVANCED"

    private val MAX_FILE_SIZE_MB = 20
    private val MAX_FILE_SIZE_BYTES = MAX_FILE_SIZE_MB * 1024 * 1024

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            showPermissionDeniedDialog()
        } else {
            Toast.makeText(this, "알림 권한이 허용되었습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private val pickImagesLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            if (!isNetworkAvailable()) {
                Toast.makeText(this, "인터넷 연결을 OK해주세요.", Toast.LENGTH_SHORT).show()
                updateStatus("인터넷 연결이 필요합니다.")
                return@registerForActivityResult
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "알림 권한이 없어 백그라운드 진행 상황이 표시되지 않을 수 있습니다.", Toast.LENGTH_LONG).show()
                }
            }

            val validUris = filterLargeFiles(uris)

            if (validUris.isNotEmpty()) {
                if (TranslationWorkState.isAnyTranslationRunning(this)) {
                    val runningTask = TranslationWorkState.runningTaskName(this) ?: "다른 번역"
                    Toast.makeText(this, getString(R.string.translation_running_block_message, runningTask), Toast.LENGTH_LONG).show()
                    return@registerForActivityResult
                }

                val selectedLang = spinnerLang.selectedItem.toString()
                val selectedTargetLang = spinnerTargetLang.selectedItem.toString()

                // [변경] ADVANCED 경고는 btnSelectOverlay 클릭 시 처리로 이동
                viewModel.processImages(validUris, selectedLang, selectedTargetLang, currentModelTier)
            } else {
                updateStatus("All selected images exceed size limit (${MAX_FILE_SIZE_MB}MB).")
            }
        } else {
            updateStatus("File selection canceled.")
        }
    }

    private val loginLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)!!
                val idToken = account.idToken
                if (idToken.isNullOrBlank()) {
                    Toast.makeText(this, "Google login failed: missing ID token", Toast.LENGTH_LONG).show()
                    return@registerForActivityResult
                }
                val credential = GoogleAuthProvider.getCredential(idToken, null)
                auth.signInWithCredential(credential).addOnCompleteListener(this) { task ->
                    if (task.isSuccessful) {
                        viewModel.checkUserBalance()
                        updateUI(true)
                    } else {
                        Log.e(TAG, "Firebase sign-in with Google credential failed", task.exception)
                        Toast.makeText(this, "Firebase login failed. Please try again.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: ApiException) {
                Log.e(TAG, "Google sign-in failed. statusCode=${e.statusCode}", e)
                val message = if (e.statusCode == 10) {
                    "Google login config error (DEVELOPER_ERROR). Check OAuth client/SHA-1."
                } else {
                    "Google login failed. code=${e.statusCode}"
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startOverlayService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Screen capture permission denied.", Toast.LENGTH_SHORT).show()
        }
    }

    private var isServiceStoppedReceiverRegistered = false

    private val serviceStoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ScreenTranslationService.ACTION_SERVICE_STOPPED) {
                isServiceRunning = false
                updateServiceButtonUI()
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        auth = FirebaseAuth.getInstance()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        billingManager = BillingManager(this, lifecycleScope)
        billingManager.startConnection()

        initView()
        initObservers()
        setupLogin()

        checkNotificationPermission()

        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("first_run_help", true)) {
            startActivity(Intent(this, HelpActivity::class.java))
            prefs.edit().putBoolean("first_run_help", false).apply()
        }
        if (auth.currentUser != null) updateUI(true) else updateUI(false)
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                    AlertDialog.Builder(this)
                        .setTitle("Notification permission required")
                        .setMessage("Notification permission is required to view translation progress.")
                        .setPositiveButton("OK") { _, _ ->
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Notification permission required")
            .setMessage("알림 권한이 거부되어 번역 진행 상황을 OK할 수 없습니다.\n\n설정 > 알림에서 권한을 허용해주세요.")
            .setPositiveButton("Open settings") { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        checkServiceState()

        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("first_run_help", true)) {
            startActivity(Intent(this, HelpActivity::class.java))
            prefs.edit().putBoolean("first_run_help", false).apply()
        }
        if (auth.currentUser != null) updateUI(true) else updateUI(false)
    }

    override fun onStart() {
        super.onStart()
        registerServiceStoppedReceiver()
    }

    override fun onStop() {
        unregisterServiceStoppedReceiver()
        super.onStop()
    }

    private fun registerServiceStoppedReceiver() {
        if (isServiceStoppedReceiverRegistered) return

        val filter = IntentFilter(ScreenTranslationService.ACTION_SERVICE_STOPPED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceStoppedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(serviceStoppedReceiver, filter)
        }
        isServiceStoppedReceiverRegistered = true
    }

    private fun unregisterServiceStoppedReceiver() {
        if (!isServiceStoppedReceiverRegistered) return

        try {
            unregisterReceiver(serviceStoppedReceiver)
        } catch (_: IllegalArgumentException) {
        } finally {
            isServiceStoppedReceiverRegistered = false
        }
    }

    private fun filterLargeFiles(uris: List<Uri>): List<Uri> {
        val validList = mutableListOf<Uri>()
        var rejectedCount = 0

        for (uri in uris) {
            val size = getFileSize(uri)
            if (size <= MAX_FILE_SIZE_BYTES) {
                validList.add(uri)
            } else {
                rejectedCount++
            }
        }

        if (rejectedCount > 0) {
            Toast.makeText(this, "${rejectedCount} images were excluded for exceeding ${MAX_FILE_SIZE_MB}MB.", Toast.LENGTH_LONG).show()
        }
        return validList
    }

    private fun getFileSize(uri: Uri): Long {
        return try {
            contentResolver.openFileDescriptor(uri, "r")?.use {
                it.statSize
            } ?: 0
        } catch (e: Exception) {
            0
        }
    }

    private fun checkServiceState() {
        isServiceRunning = isMyServiceRunning(ScreenTranslationService::class.java)
        updateServiceButtonUI()
    }

    @Suppress("DEPRECATION")
    private fun isMyServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) return true
        }
        return false
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
            return activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            return networkInfo != null && networkInfo.isConnected
        }
    }

    private fun initView() {
        // [New] Views for updated header
        cardLogin = findViewById(R.id.card_login)
        btnLoginContainer = findViewById(R.id.btn_google_login)

        cardSilverInfo = findViewById(R.id.card_silver_info)
        tvSilverBalance = findViewById(R.id.tv_silver_balance)
        tvGoldBalance = findViewById(R.id.tv_gold_balance) // [추가]
        btnStore = findViewById(R.id.btn_store)

        btnSettings = findViewById(R.id.btn_settings)

        tvStatus = findViewById(R.id.tv_status)
        progressBar = findViewById(R.id.progressBar)
        btnStopWork = findViewById(R.id.btn_stop_work)

        spinnerLang = findViewById(R.id.spinner_lang)

        // [추가] 모델 선택
        btnModelSelect = findViewById(R.id.btn_model_select)
        cardModelSelect = findViewById(R.id.card_model_select)
        btnModelSelect.setOnClickListener { showModelSelectionDialog() }

        btnSelectOverlay = findViewById(R.id.btn_select_images)
        btnScreenTransOverlay = findViewById(R.id.btn_screen_trans)
        btnAutoScreenTrans = findViewById(R.id.btn_auto_screen_trans) // [추가]
        btnNovelTranslation = findViewById(R.id.btn_novel_translation)
        btnVnGameTranslation = findViewById(R.id.btn_vn_game_translation_main)

        findViewById<Button>(R.id.btn_nav_library).setOnClickListener {
            startActivity(Intent(this, LibraryActivity::class.java))
        }

        // [수정] 도움말 버튼 연결 (헤더 위치)
        findViewById<View>(R.id.btnScreenTransHelp).setOnClickListener { showGuideDialog() }
        // 이미지 선택 카드 도움말 버튼 (기존)
        findViewById<View>(R.id.btn_help_image).setOnClickListener { startActivity(Intent(this, HelpActivity::class.java)) }

        tvTransTitle = findViewById(R.id.tv_trans_title)
        ivTransIcon = findViewById(R.id.iv_trans_icon)
        ivAutoTransIcon = findViewById(R.id.iv_auto_trans_icon) // [추가]
        tvAutoTransTitle = findViewById(R.id.tv_auto_trans_title) // [추가]

        originalIconColor = ivTransIcon.imageTintList

        // 원문 언어 스피너
        val languages = arrayOf("Japanese", "English", "Korean", "Chinese")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, languages)
        spinnerLang.adapter = adapter

        spinnerLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isServiceRunning) {
                    val selectedLang = languages[position]
                    val intent = Intent(this@MainActivity, ScreenTranslationService::class.java).apply {
                        action = ScreenTranslationService.ACTION_UPDATE_LANG
                        putExtra("sourceLang", selectedLang)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 목표 언어 스피너
        spinnerTargetLang = findViewById(R.id.spinner_target_lang)
        val targetLanguages = arrayOf("English", "Korean", "Japanese", "Chinese")
        val targetAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, targetLanguages)
        spinnerTargetLang.adapter = targetAdapter

        // 저장된 목표 언어 복원
        val savedTargetLang = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .getString("target_lang", "Korean") ?: "Korean"
        val targetIndex = targetLanguages.indexOf(savedTargetLang)
        if (targetIndex >= 0) spinnerTargetLang.setSelection(targetIndex)

        spinnerTargetLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedTargetLang = targetLanguages[position]
                getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                    .edit().putString("target_lang", selectedTargetLang).apply()

                if (isServiceRunning) {
                    val intent = Intent(this@MainActivity, ScreenTranslationService::class.java).apply {
                        action = ScreenTranslationService.ACTION_UPDATE_TARGET_LANG
                        putExtra("targetLang", selectedTargetLang)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // [변경] 대량 번역: 로그인 체크 + ADVANCED 경고 시점 이동 (이미지 불러오기 버튼 클릭 시)
        btnSelectOverlay.setOnClickListener {
            if (auth.currentUser == null) {
                Toast.makeText(this, "로그인을 먼저 해주세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (currentModelTier == "ADVANCED") {
                val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                if (prefs.getBoolean("skip_advanced_mass_warning", false)) {
                    pickImagesLauncher.launch("image/*")
                } else {
                    val cb = CheckBox(this).apply { text = "다시 보지 않기"; setPadding(40, 20, 40, 0) }
                    AlertDialog.Builder(this)
                        .setTitle("경고")
                        .setMessage("대량 번역에 실버 모델을 사용하면 퀄리티가 좋지 않으니 골드 모델을 추천합니다.")
                        .setView(cb)
                        .setPositiveButton("확인") { _, _ ->
                            if (cb.isChecked) prefs.edit().putBoolean("skip_advanced_mass_warning", true).apply()
                            pickImagesLauncher.launch("image/*")
                        }
                        .setNegativeButton("취소", null)
                        .show()
                }
            } else {
                pickImagesLauncher.launch("image/*")
            }
        }

        btnNovelTranslation.setOnClickListener {
            if (TranslationWorkState.isAnyTranslationRunning(this)) {
                val runningTask = TranslationWorkState.runningTaskName(this) ?: "다른 번역"
                Toast.makeText(this, getString(R.string.translation_running_block_message, runningTask), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, NovelTranslationActivity::class.java))
        }

        btnVnGameTranslation.setOnClickListener {
            if (TranslationWorkState.isAnyTranslationRunning(this)) {
                val runningTask = TranslationWorkState.runningTaskName(this) ?: "다른 번역"
                Toast.makeText(this, getString(R.string.translation_running_block_message, runningTask), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, VnGameTranslationActivity::class.java))
        }

        btnStopWork.setOnClickListener {
            viewModel.stopTranslation()

            // UI 즉시 업데이트
            btnStopWork.isEnabled = false
            tvStatus.text = "Stopping..."
        }

        // [수정] 화면 바로번역 버튼 리스너 (최초 실행 도움말 체크 + 로그인 체크 포함)
        btnScreenTransOverlay.setOnClickListener {
            val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val hasUsed = prefs.getBoolean(KEY_HAS_USED_SCREEN_TRANS, false)

            // 1. 처음 사용하는 경우: 도움말 표시 후 리턴 (서비스 시작 안 함)
            if (!hasUsed) {
                showGuideDialog()
                prefs.edit().putBoolean(KEY_HAS_USED_SCREEN_TRANS, true).apply()
                return@setOnClickListener
            }

            // 2. 기존 사용자: 서비스 시작/종료 로직 수행
            if (isServiceRunning) {
                stopOverlayService()
            } else {
                // [추가] 로그인 체크
                if (auth.currentUser == null) {
                    Toast.makeText(this, "로그인을 먼저 해주세요", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!isNetworkAvailable() && currentModelTier != "STANDARD") {
                    Toast.makeText(this, "인터넷 연결을 OK해주세요.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                pendingAutoMode = false
                checkOverlayPermission()
            }
        }

        // [추가] 자동 화면번역 버튼 리스너
        btnAutoScreenTrans.setOnClickListener {
            val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val hasUsed = prefs.getBoolean(KEY_HAS_USED_SCREEN_TRANS, false)

            if (!hasUsed) {
                showGuideDialog()
                prefs.edit().putBoolean(KEY_HAS_USED_SCREEN_TRANS, true).apply()
                return@setOnClickListener
            }

            if (isServiceRunning) {
                stopOverlayService()
            } else {
                if (auth.currentUser == null) {
                    Toast.makeText(this, "로그인을 먼저 해주세요", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!isNetworkAvailable() && currentModelTier != "STANDARD") {
                    Toast.makeText(this, "인터넷 연결을 OK해주세요.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                pendingAutoMode = true
                checkOverlayPermission()
            }
        }

        btnStore.setOnClickListener {
            showStoreDialog()
        }

        btnSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }
    }

    // [추가] 모델 선택 다이얼로그
    private fun showModelSelectionDialog() {
        val items = arrayOf(
            "Standard translation (Free) - Offline",
            "Advanced translation (1 Silver/page) - Flash-Lite",
            "Pro translation (1 Gold/page) - Flash"
        )

        val silverBal = viewModel.userBalance.value
        val goldBal = viewModel.userGoldBalance.value

        AlertDialog.Builder(this)
            .setTitle("Select translation model")
            .setSingleChoiceItems(items, getModelIndex(currentModelTier)) { dialog, which ->
                val selectedTier = when (which) {
                    0 -> "STANDARD"
                    1 -> "ADVANCED"
                    2 -> "PRO"
                    else -> "ADVANCED"
                }

                // [변경] PRO 모델 선택 시 항상 속도 경고 + "다시 보지 않기"
                if (selectedTier == "PRO") {
                    val warningPrefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                    if (warningPrefs.getBoolean("skip_pro_screen_warning", false)) {
                        // 경고 스킵 → 즉시 적용
                        currentModelTier = selectedTier
                        updateModelButtonUI()
                        if (isServiceRunning) {
                            val intent2 = Intent(this, ScreenTranslationService::class.java).apply {
                                action = ScreenTranslationService.ACTION_UPDATE_MODEL
                                putExtra("modelTier", currentModelTier)
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent2) else startService(intent2)
                        }
                        dialog.dismiss()
                    } else {
                        dialog.dismiss()
                        val cb = CheckBox(this).apply { text = "다시 보지 않기"; setPadding(40, 20, 40, 0) }
                        AlertDialog.Builder(this)
                            .setTitle("경고")
                            .setMessage("골드 모델은 정밀하지만 속도가 느려 화면 번역에는 권장하지 않습니다.")
                            .setView(cb)
                            .setPositiveButton("확인") { _, _ ->
                                if (cb.isChecked) warningPrefs.edit().putBoolean("skip_pro_screen_warning", true).apply()
                                currentModelTier = selectedTier
                                updateModelButtonUI()
                                if (isServiceRunning) {
                                    val intent2 = Intent(this, ScreenTranslationService::class.java).apply {
                                        action = ScreenTranslationService.ACTION_UPDATE_MODEL
                                        putExtra("modelTier", currentModelTier)
                                    }
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent2) else startService(intent2)
                                }
                            }
                            .setNegativeButton("취소", null)
                            .show()
                    }
                } else {
                    currentModelTier = selectedTier
                    updateModelButtonUI()

                    // 화면 번역 서비스가 실행 중이면 실시간 변경 요청
                    if (isServiceRunning) {
                        val intent = Intent(this, ScreenTranslationService::class.java).apply {
                            action = ScreenTranslationService.ACTION_UPDATE_MODEL
                            putExtra("modelTier", currentModelTier)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
                    }

                    dialog.dismiss()
                }
            }
            .setNeutralButton("잔액 OK") { _, _ ->
                Toast.makeText(this, "Silver: $silverBal | Gold: $goldBal", Toast.LENGTH_LONG).show()
            }
            .show()
    }

    // [추가] 도움말 다이얼로그
    private fun showGuideDialog() {
        AlertDialog.Builder(this)
            .setTitle("How to use instant screen translation")
            .setMessage("1. 돋보기 버튼을 눌러 번역을 시작하세요.\n2. 초록색 영역을 드래그하여 번역할 위치를 설정하세요.\n3. 영역을 고정하려면 '영역 고정' 체크박스를, 이동하려면 체크를 해제하세요.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun getModelIndex(tier: String): Int {
        return when(tier) {
            "STANDARD" -> 0
            "ADVANCED" -> 1
            "PRO" -> 2
            else -> 1
        }
    }

    private fun updateModelButtonUI() {
        val text = when(currentModelTier) {
            "STANDARD" -> "Model: Standard (Free)"
            "ADVANCED" -> "Model: Advanced (Silver)"
            "PRO" -> "Model: Pro (Gold)"
            else -> "Model: Advanced (Silver)"
        }
        btnModelSelect.text = text
    }

    private fun showStoreDialog() {
        val products = billingManager.productDetailsList.value

        if (products.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Store not ready")
                .setMessage("상품 정보를 불러올 수 없습니다.\n\n(Google Play Console 설정을 OK해주세요.)")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        // [변경] 가격순 정렬 (실버 -> 골드 순서 유지를 위해 상품 ID 활용 추천하지만 일단 가격순)
        val sortedProducts = products.sortedBy { it.oneTimePurchaseOfferDetails?.priceAmountMicros ?: 0 }

        val productNames = sortedProducts.map {
            "${it.name} - ${it.oneTimePurchaseOfferDetails?.formattedPrice}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Recharge (Silver/Gold)")
            .setItems(productNames) { _, which ->
                val selectedProduct = sortedProducts[which]
                billingManager.launchPurchaseFlow(this, selectedProduct)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun initObservers() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is UiState.Idle -> {
                            progressBar.visibility = View.GONE
                            btnStopWork.visibility = View.GONE
                            btnSelectOverlay.isEnabled = (auth.currentUser != null)
                        }
                        is UiState.Loading -> {
                            updateStatus(state.message, true)
                            btnStopWork.visibility = View.VISIBLE
                            btnStopWork.isEnabled = true
                        }
                        is UiState.Success<*> -> {
                            // 서비스에서 COMPLETE 방송을 보내므로 여기서 중복 처리 최소화
                            if(progressBar.visibility == View.VISIBLE) {
                                updateStatus("Done: " + (state.data as? String ?: ""), false)
                            }
                        }
                        is UiState.Error -> {
                            updateStatus("Error", false)
                            btnStopWork.visibility = View.GONE
                            showErrorDialog(state.message, state.onRetry)
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.userBalance.collect { balance ->
                    if (auth.currentUser != null) {
                        tvSilverBalance.text = getString(R.string.balance_format, balance)
                    }
                }
            }
        }

        // [추가] 골드 잔액 관찰
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.userGoldBalance.collect { balance ->
                    if (auth.currentUser != null) {
                        tvGoldBalance.text = "$balance G"
                    }
                }
            }
        }

    }

    private fun showErrorDialog(message: String, onRetry: (() -> Unit)?) {
        val builder = AlertDialog.Builder(this)
            .setTitle("Error 발생")
            .setMessage(message)
            .setPositiveButton("OK", null)
        if (onRetry != null) {
            builder.setNegativeButton("Retry") { _, _ -> onRetry.invoke() }
        }
        builder.show()
    }

    private fun setupLogin() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(GOOGLE_WEB_CLIENT_ID)
            .requestEmail()
            .build()
        val googleSignInClient = GoogleSignIn.getClient(this, gso)

        btnLoginContainer.setOnClickListener {
            if (auth.currentUser == null) {
                val signInIntent = googleSignInClient.signInIntent
                loginLauncher.launch(signInIntent)
            }
        }
    }

    private fun stopOverlayService() {
        val intent = Intent(this, ScreenTranslationService::class.java).apply {
            action = ScreenTranslationService.ACTION_STOP
        }
        startService(intent)
        isServiceRunning = false
        updateServiceButtonUI()
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivityForResult(intent, 1001)
                Toast.makeText(this, "Allow draw-over-other-apps permission.", Toast.LENGTH_LONG).show()
            } else {
                requestScreenCapturePermission()
            }
        } else {
            requestScreenCapturePermission()
        }
    }

    private fun requestScreenCapturePermission() {
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(captureIntent)
    }

    private fun startOverlayService(resultCode: Int, data: Intent) {
        val selectedLang = spinnerLang.selectedItem.toString()
        val selectedTargetLang = spinnerTargetLang.selectedItem.toString()
        // [변경] pendingAutoMode에 따라 ACTION_START_AUTO / ACTION_START 분기
        val serviceAction = if (pendingAutoMode) ScreenTranslationService.ACTION_START_AUTO else ScreenTranslationService.ACTION_START
        val intent = Intent(this, ScreenTranslationService::class.java).apply {
            action = serviceAction
            putExtra("resultCode", resultCode)
            putExtra("data", data)
            putExtra("sourceLang", selectedLang)
            putExtra("targetLang", selectedTargetLang)
            putExtra("modelTier", currentModelTier)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        isServiceRunning = true
        pendingAutoMode = false
        updateServiceButtonUI()
        moveTaskToBack(true)
    }

    private fun updateServiceButtonUI() {
        if (isServiceRunning) {
            tvTransTitle.text = "번역 중지"
            ivTransIcon.imageTintList = ColorStateList.valueOf(Color.RED)
            tvAutoTransTitle.text = "번역 중지"
            ivAutoTransIcon.imageTintList = ColorStateList.valueOf(Color.RED)
        } else {
            tvTransTitle.text = getString(R.string.action_screen_trans_instant)
            ivTransIcon.imageTintList = originalIconColor
            tvAutoTransTitle.text = getString(R.string.action_screen_trans_auto)
            ivAutoTransIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#FF9800"))
        }
    }

    private fun updateStatus(msg: String, isLoading: Boolean = false) {
        tvStatus.text = msg
        progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        btnSelectOverlay.isEnabled = !isLoading && (auth.currentUser != null)
        spinnerLang.isEnabled = !isLoading
    }

    private fun updateUI(isLoggedIn: Boolean) {
        if (isLoggedIn) {
            cardLogin.visibility = View.GONE
            cardSilverInfo.visibility = View.VISIBLE
            btnSelectOverlay.isEnabled = true
            findViewById<View>(R.id.btnScreenTransHelp).visibility = View.VISIBLE // [추가]
        } else {
            cardLogin.visibility = View.VISIBLE
            cardSilverInfo.visibility = View.GONE
            btnSelectOverlay.isEnabled = false
            findViewById<View>(R.id.btnScreenTransHelp).visibility = View.GONE // [추가]
        }
    }

    override fun onDestroy() {
        billingManager.endConnection()
        super.onDestroy()
    }
}
