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
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class MainActivity : LocalizedActivity() {
    companion object {
        private const val TAG = "MainActivity"
        // Matches app/google-services.json (OAuth web client, type 3)
        private const val GOOGLE_WEB_CLIENT_ID = "691923720143-m2dbnoal2cbc8vn6piu7c7gsi55p7lid.apps.googleusercontent.com"
        private const val PREF_APP = "app_prefs"
        private const val KEY_HAS_USED_SCREEN_TRANS = "has_used_screen_trans"
        private const val KEY_SOURCE_LANG_CODE = "source_lang_code"
        private const val KEY_TARGET_LANG_CODE = "target_lang_code"
        private const val LEGACY_KEY_TARGET_LANG = "target_lang"
    }

    private data class UiLanguageOption(
        val label: String,
        val ocrCode: String
    )

    private val viewModel: MainViewModel by viewModels()

    // Views
    private lateinit var cardLogin: CardView
    private lateinit var btnLoginContainer: LinearLayout

    private lateinit var cardSilverInfo: CardView
    private lateinit var tvSilverBalance: TextView
    private lateinit var tvGoldBalance: TextView // [異붽?]
    private lateinit var btnStore: LinearLayout

    private lateinit var btnSettings: ImageView

    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnStopWork: Button

    // Action Cards
    private lateinit var btnSelectOverlay: View
    private lateinit var btnScreenTransOverlay: View
    private lateinit var btnAutoScreenTrans: View  // [異붽?] ?먮룞 ?붾㈃踰덉뿭 踰꾪듉
    private lateinit var btnNovelTranslation: View
    private lateinit var btnVnGameTranslation: View

    // UI Elements for updates
    private lateinit var tvTransTitle: TextView
    private lateinit var ivTransIcon: ImageView
    private lateinit var ivAutoTransIcon: ImageView  // [異붽?] ?먮룞 踰덉뿭 ?꾩씠肄?
    private lateinit var tvAutoTransTitle: TextView   // [異붽?] ?먮룞 踰덉뿭 ??댄?

    private var pendingAutoMode = false  // [異붽?] ?먮룞 紐⑤뱶 ?湲??뚮옒洹?

    private lateinit var spinnerLang: com.google.android.material.textfield.MaterialAutoCompleteTextView
    private lateinit var spinnerTargetLang: com.google.android.material.textfield.MaterialAutoCompleteTextView
    private lateinit var btnModelSelect: TextView // [異붽?]
    private lateinit var cardModelSelect: CardView // [異붽?]

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var auth: FirebaseAuth

    private lateinit var billingManager: BillingManager

    private var isServiceRunning = false
    private var originalIconColor: ColorStateList? = null

    // ?꾩옱 ?좏깮??紐⑤뜽 (湲곕낯媛? ADVANCED)
    private var currentModelTier = "ADVANCED"
    private var selectedSourceLangCode = "Japanese"
    private var selectedTargetLangCode = "English"
    private val standardModelPrefetchScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val standardModelDownloadInFlight = ConcurrentHashMap<String, Boolean>()
    private val standardModelDownloadReady = ConcurrentHashMap<String, Boolean>()

    private val MAX_FILE_SIZE_MB = 20
    private val MAX_FILE_SIZE_BYTES = MAX_FILE_SIZE_MB * 1024 * 1024
    private val sourceLanguageOptions by lazy {
        listOf(
            UiLanguageOption(getString(R.string.language_name_japanese), "Japanese"),
            UiLanguageOption(getString(R.string.language_name_english), "English"),
            UiLanguageOption(getString(R.string.language_name_korean), "Korean"),
            UiLanguageOption(getString(R.string.language_name_chinese), "Chinese")
        )
    }
    private val targetLanguageOptions by lazy {
        listOf(
            UiLanguageOption(getString(R.string.language_name_english), "English"),
            UiLanguageOption(getString(R.string.language_name_korean), "Korean"),
            UiLanguageOption(getString(R.string.language_name_japanese), "Japanese"),
            UiLanguageOption(getString(R.string.language_name_chinese), "Chinese")
        )
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            showPermissionDeniedDialog()
        } else {
            Toast.makeText(this, getString(R.string.main_notification_permission_granted), Toast.LENGTH_SHORT).show()
        }
    }

    private val pickImagesLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            if (!isNetworkAvailable()) {
                Toast.makeText(this, getString(R.string.main_error_network_required), Toast.LENGTH_SHORT).show()
                updateStatus(getString(R.string.main_error_network_required))
                return@registerForActivityResult
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, getString(R.string.main_notification_permission_missing_hint), Toast.LENGTH_LONG).show()
                }
            }

            val validUris = filterLargeFiles(uris)

            if (validUris.isNotEmpty()) {
                if (TranslationWorkState.isAnyTranslationRunning(this)) {
                    val runningTask = TranslationWorkState.runningTaskName(this)
                        ?: getString(R.string.translation_running_other_task)
                    Toast.makeText(this, getString(R.string.translation_running_block_message, runningTask), Toast.LENGTH_LONG).show()
                    return@registerForActivityResult
                }

                val selectedLang = currentSourceLang()
                val selectedTargetLang = currentTargetLang()

                // [蹂寃? ADVANCED 寃쎄퀬??btnSelectOverlay ?대┃ ??泥섎━濡??대룞
                viewModel.processImages(validUris, selectedLang, selectedTargetLang, currentModelTier)
            } else {
                updateStatus(getString(R.string.main_error_file_size_limit, MAX_FILE_SIZE_MB))
            }
        } else {
            updateStatus(getString(R.string.main_status_file_selection_canceled))
        }
    }

    private val loginLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)!!
                val idToken = account.idToken
                if (idToken.isNullOrBlank()) {
                    Toast.makeText(this, getString(R.string.main_google_login_missing_token), Toast.LENGTH_LONG).show()
                    return@registerForActivityResult
                }
                val credential = GoogleAuthProvider.getCredential(idToken, null)
                auth.signInWithCredential(credential).addOnCompleteListener(this) { task ->
                    if (task.isSuccessful) {
                        viewModel.checkUserBalance()
                        updateUI(true)
                    } else {
                        Log.e(TAG, "Firebase sign-in with Google credential failed", task.exception)
                        Toast.makeText(this, getString(R.string.main_firebase_login_failed), Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: ApiException) {
                Log.e(TAG, "Google sign-in failed. statusCode=${e.statusCode}", e)
                val message = if (e.statusCode == 10) {
                    getString(R.string.main_google_login_config_error)
                } else {
                    getString(R.string.main_google_login_failed_code, e.statusCode)
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startOverlayService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, getString(R.string.main_screen_capture_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    private val settingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val changed = result.data?.getBooleanExtra(SettingsActivity.EXTRA_LANGUAGE_CHANGED, false) == true
        if (changed) {
            recreate()
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

        val prefs = getSharedPreferences(PREF_APP, Context.MODE_PRIVATE)
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
                        .setTitle(R.string.main_notification_permission_title)
                        .setMessage(R.string.main_notification_permission_rationale)
                        .setPositiveButton(R.string.common_ok) { _, _ ->
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        .setNegativeButton(R.string.common_cancel, null)
                        .show()
                } else {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.main_notification_permission_title)
            .setMessage(R.string.main_notification_permission_denied_message)
            .setPositiveButton(R.string.main_open_settings) { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        checkServiceState()
        updateNovelRunningStatusUi()

        val prefs = getSharedPreferences(PREF_APP, Context.MODE_PRIVATE)
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
            Toast.makeText(
                this,
                getString(R.string.main_error_file_size_rejected, rejectedCount, MAX_FILE_SIZE_MB),
                Toast.LENGTH_LONG
            ).show()
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
        tvGoldBalance = findViewById(R.id.tv_gold_balance) // [異붽?]
        btnStore = findViewById(R.id.btn_store)

        btnSettings = findViewById(R.id.btn_settings)

        tvStatus = findViewById(R.id.tv_status)
        progressBar = findViewById(R.id.progressBar)
        btnStopWork = findViewById(R.id.btn_stop_work)

        spinnerLang = findViewById(R.id.spinner_lang)

        // [異붽?] 紐⑤뜽 ?좏깮
        btnModelSelect = findViewById(R.id.btn_model_select)
        cardModelSelect = findViewById(R.id.card_model_select)
        btnModelSelect.setOnClickListener { showModelSelectionSheet() }
        updateModelButtonUI()

        btnSelectOverlay = findViewById(R.id.btn_select_images)
        btnScreenTransOverlay = findViewById(R.id.btn_screen_trans)
        btnAutoScreenTrans = findViewById(R.id.btn_auto_screen_trans) // [異붽?]
        btnNovelTranslation = findViewById(R.id.btn_novel_translation)
        btnVnGameTranslation = findViewById(R.id.btn_vn_game_translation_main)

        findViewById<Button>(R.id.btn_nav_library).setOnClickListener {
            startActivity(Intent(this, LibraryActivity::class.java))
        }

        // [?섏젙] ?꾩?留?踰꾪듉 ?곌껐 (?ㅻ뜑 ?꾩튂)
        findViewById<View>(R.id.btnScreenTransHelp).setOnClickListener { showGuideDialog() }

        tvTransTitle = findViewById(R.id.tv_trans_title)
        ivTransIcon = findViewById(R.id.iv_trans_icon)
        ivAutoTransIcon = findViewById(R.id.iv_auto_trans_icon) // [異붽?]
        tvAutoTransTitle = findViewById(R.id.tv_auto_trans_title) // [異붽?]

        originalIconColor = ivTransIcon.imageTintList

        // ?먮Ц ?몄뼱 ?ㅽ뵾??
        spinnerTargetLang = findViewById(R.id.spinner_target_lang)
        setupLanguageDropdown(spinnerLang)
        setupLanguageDropdown(spinnerTargetLang)

        val sourceLabels = sourceLanguageOptions.map { it.label }
        val targetLabels = targetLanguageOptions.map { it.label }
        spinnerLang.setAdapter(ArrayAdapter(this, R.layout.item_dropdown_option, sourceLabels))
        spinnerTargetLang.setAdapter(ArrayAdapter(this, R.layout.item_dropdown_option, targetLabels))
        val prefs = getSharedPreferences(PREF_APP, Context.MODE_PRIVATE)
        selectedSourceLangCode = resolveSavedLanguageCode(
            raw = prefs.getString(KEY_SOURCE_LANG_CODE, null),
            options = sourceLanguageOptions,
            fallback = sourceLanguageOptions.first().ocrCode
        )
        selectedTargetLangCode = resolveSavedLanguageCode(
            raw = prefs.getString(KEY_TARGET_LANG_CODE, null)
                ?: prefs.getString(LEGACY_KEY_TARGET_LANG, null),
            options = targetLanguageOptions,
            fallback = targetLanguageOptions.first().ocrCode
        )

        val initialSource = sourceLanguageOptions.firstOrNull { it.ocrCode == selectedSourceLangCode }
            ?: sourceLanguageOptions.first()
        val initialTarget = targetLanguageOptions.firstOrNull { it.ocrCode == selectedTargetLangCode }
            ?: targetLanguageOptions.first()
        spinnerLang.setText(initialSource.label, false)
        spinnerTargetLang.setText(initialTarget.label, false)

        spinnerLang.setOnItemClickListener { _, _, position, _ ->
            val selectedSource = sourceLanguageOptions.getOrNull(position)
                ?: sourceLanguageOptions.firstOrNull { it.label == spinnerLang.text?.toString().orEmpty() }
                ?: sourceLanguageOptions.first()
            selectedSourceLangCode = selectedSource.ocrCode
            prefs.edit().putString(KEY_SOURCE_LANG_CODE, selectedSourceLangCode).apply()

            if (isServiceRunning) {
                val intent = Intent(this@MainActivity, ScreenTranslationService::class.java).apply {
                    action = ScreenTranslationService.ACTION_UPDATE_LANG
                    putExtra("sourceLang", selectedSourceLangCode)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            }
            if (currentModelTier == "STANDARD") {
                maybePrefetchStandardModel(showHint = true)
            }
        }

        spinnerTargetLang.setOnItemClickListener { _, _, position, _ ->
            val selectedTarget = targetLanguageOptions.getOrNull(position)
                ?: targetLanguageOptions.firstOrNull { it.label == spinnerTargetLang.text?.toString().orEmpty() }
                ?: targetLanguageOptions.first()
            selectedTargetLangCode = selectedTarget.ocrCode
            prefs.edit().putString(KEY_TARGET_LANG_CODE, selectedTargetLangCode).apply()

            if (isServiceRunning) {
                val intent = Intent(this@MainActivity, ScreenTranslationService::class.java).apply {
                    action = ScreenTranslationService.ACTION_UPDATE_TARGET_LANG
                    putExtra("targetLang", selectedTargetLangCode)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            }
            if (currentModelTier == "STANDARD") {
                maybePrefetchStandardModel(showHint = true)
            }
        }

        // [蹂寃? ???踰덉뿭: 濡쒓렇??泥댄겕 + ADVANCED 寃쎄퀬 ?쒖젏 ?대룞 (?대?吏 遺덈윭?ㅺ린 踰꾪듉 ?대┃ ??
        btnSelectOverlay.setOnClickListener {
            if (auth.currentUser == null) {
                Toast.makeText(this, getString(R.string.main_error_login_required), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (currentModelTier == "ADVANCED") {
                val prefs = getSharedPreferences(PREF_APP, Context.MODE_PRIVATE)
                if (prefs.getBoolean("skip_advanced_mass_warning", false)) {
                    pickImagesLauncher.launch("image/*")
                } else {
                    val (dontShowAgainView, cb) = createCenteredDontShowAgainView()
                    MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.model_balanced_notice_title))
                        .setMessage(getString(R.string.model_balanced_notice_message))
                        .setView(dontShowAgainView)
                        .setPositiveButton(R.string.common_ok) { _, _ ->
                            if (cb.isChecked) prefs.edit().putBoolean("skip_advanced_mass_warning", true).apply()
                            pickImagesLauncher.launch("image/*")
                        }
                        .setNegativeButton(R.string.common_cancel, null)
                        .show()
                }
            } else {
                pickImagesLauncher.launch("image/*")
            }
        }

        btnNovelTranslation.setOnClickListener {
            if (TranslationWorkState.isAnyTranslationRunning(this)) {
                val runningTask = TranslationWorkState.runningTaskName(this)
                    ?: getString(R.string.translation_running_other_task)
                Toast.makeText(this, getString(R.string.translation_running_block_message, runningTask), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, NovelTranslationActivity::class.java))
        }

        btnVnGameTranslation.setOnClickListener {
            if (TranslationWorkState.isAnyTranslationRunning(this)) {
                val runningTask = TranslationWorkState.runningTaskName(this)
                    ?: getString(R.string.translation_running_other_task)
                Toast.makeText(this, getString(R.string.translation_running_block_message, runningTask), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, VnGameTranslationActivity::class.java))
        }

        btnStopWork.setOnClickListener {
            viewModel.stopTranslation()

            // UI 利됱떆 ?낅뜲?댄듃
            btnStopWork.isEnabled = false
            tvStatus.text = getString(R.string.main_status_stopping)
        }

        // [?섏젙] ?붾㈃ 諛붾줈踰덉뿭 踰꾪듉 由ъ뒪??(理쒖큹 ?ㅽ뻾 ?꾩?留?泥댄겕 + 濡쒓렇??泥댄겕 ?ы븿)
        btnScreenTransOverlay.setOnClickListener {
            val prefs = getSharedPreferences(PREF_APP, Context.MODE_PRIVATE)
            val hasUsed = prefs.getBoolean(KEY_HAS_USED_SCREEN_TRANS, false)

            // 1. 泥섏쓬 ?ъ슜?섎뒗 寃쎌슦: ?꾩?留??쒖떆 ??由ы꽩 (?쒕퉬???쒖옉 ????
            if (!hasUsed) {
                showGuideDialog()
                prefs.edit().putBoolean(KEY_HAS_USED_SCREEN_TRANS, true).apply()
                return@setOnClickListener
            }

            // 2. 湲곗〈 ?ъ슜?? ?쒕퉬???쒖옉/醫낅즺 濡쒖쭅 ?섑뻾
            if (isServiceRunning) {
                stopOverlayService()
            } else {
                // [異붽?] 濡쒓렇??泥댄겕
                if (auth.currentUser == null) {
                    Toast.makeText(this, getString(R.string.main_error_login_required), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!isNetworkAvailable() && currentModelTier != "STANDARD") {
                    Toast.makeText(this, getString(R.string.main_error_network_required), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                pendingAutoMode = false
                checkOverlayPermission()
            }
        }

        // [異붽?] ?먮룞 ?붾㈃踰덉뿭 踰꾪듉 由ъ뒪??
        btnAutoScreenTrans.setOnClickListener {
            val prefs = getSharedPreferences(PREF_APP, Context.MODE_PRIVATE)
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
                    Toast.makeText(this, getString(R.string.main_error_login_required), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!isNetworkAvailable() && currentModelTier != "STANDARD") {
                    Toast.makeText(this, getString(R.string.main_error_network_required), Toast.LENGTH_SHORT).show()
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
            settingsLauncher.launch(intent)
        }
    }

    private fun setupLanguageDropdown(dropdown: com.google.android.material.textfield.MaterialAutoCompleteTextView) {
        dropdown.threshold = 0
        dropdown.keyListener = null
        dropdown.isCursorVisible = false

        dropdown.setOnClickListener {
            dropdown.showDropDown()
        }
        dropdown.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) dropdown.showDropDown()
        }
        dropdown.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                dropdown.showDropDown()
            }
            false
        }
    }

    private fun showModelSelectionSheet() {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_model_selection_sheet, null)
        sheet.setContentView(view)

        val silverBal = viewModel.userBalance.value
        val goldBal = viewModel.userGoldBalance.value
        val balanceText = getString(R.string.model_sheet_balance_format, silverBal, goldBal)
        view.findViewById<TextView>(R.id.tv_model_sheet_balance).text = balanceText

        val cardStandard = view.findViewById<MaterialCardView>(R.id.card_mode_standard)
        val cardBalanced = view.findViewById<MaterialCardView>(R.id.card_mode_balanced)
        val cardPrecise = view.findViewById<MaterialCardView>(R.id.card_mode_precise)

        fun decorate(card: MaterialCardView, isSelected: Boolean) {
            card.strokeWidth = if (isSelected) 3 else 1
            val colorRes = if (isSelected) R.color.brand_primary else R.color.border_subtle
            card.strokeColor = ContextCompat.getColor(this, colorRes)
        }

        decorate(cardStandard, currentModelTier == "STANDARD")
        decorate(cardBalanced, currentModelTier == "ADVANCED")
        decorate(cardPrecise, currentModelTier == "PRO")

        cardStandard.setOnClickListener {
            applyModelTierChange("STANDARD")
            sheet.dismiss()
        }
        cardBalanced.setOnClickListener {
            applyModelTierChange("ADVANCED")
            sheet.dismiss()
        }
        cardPrecise.setOnClickListener {
            val warningPrefs = getSharedPreferences(PREF_APP, Context.MODE_PRIVATE)
            if (warningPrefs.getBoolean("skip_pro_screen_warning", false)) {
                applyModelTierChange("PRO")
                sheet.dismiss()
            } else {
                val (dontShowAgainView, cb) = createCenteredDontShowAgainView()
                MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.model_precise_warning_title))
                    .setMessage(getString(R.string.model_precise_warning_message))
                    .setView(dontShowAgainView)
                    .setPositiveButton(R.string.common_ok) { _, _ ->
                        if (cb.isChecked) warningPrefs.edit().putBoolean("skip_pro_screen_warning", true).apply()
                        applyModelTierChange("PRO")
                    }
                    .setNegativeButton(R.string.common_cancel, null)
                    .show()
                sheet.dismiss()
            }
        }

        sheet.show()
    }

    private fun createCenteredDontShowAgainView(): Pair<LinearLayout, CheckBox> {
        val checkBox = CheckBox(this).apply {
            text = getString(R.string.dialog_never_show_again)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(checkBox)
        }

        return container to checkBox
    }

    // [異붽?] ?꾩?留??ㅼ씠?쇰줈洹?
    private fun showGuideDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.main_guide_dialog_title)
            .setMessage(R.string.main_guide_dialog_message)
            .setPositiveButton(R.string.common_ok, null)
            .show()
    }

    private fun updateModelButtonUI() {
        val text = when(currentModelTier) {
            "STANDARD" -> getString(R.string.main_mode_standard)
            "ADVANCED" -> getString(R.string.main_mode_balanced)
            "PRO" -> getString(R.string.main_mode_precise)
            else -> getString(R.string.main_mode_balanced)
        }
        btnModelSelect.text = text
    }

    private fun applyModelTierChange(selectedTier: String) {
        currentModelTier = selectedTier
        updateModelButtonUI()
        if (currentModelTier == "STANDARD") {
            maybePrefetchStandardModel(showHint = true)
        }

        if (isServiceRunning) {
            val intent = Intent(this, ScreenTranslationService::class.java).apply {
                action = ScreenTranslationService.ACTION_UPDATE_MODEL
                putExtra("modelTier", currentModelTier)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        }
    }

    private fun currentSourceLang(): String {
        return selectedSourceLangCode
    }

    private fun currentTargetLang(): String {
        return selectedTargetLangCode
    }

    private fun maybePrefetchStandardModel(showHint: Boolean) {
        if (currentModelTier != "STANDARD") return

        val sourceCode = mapLangCodeForLocalModel(currentSourceLang())
        val targetCode = mapLangCodeForLocalModel(currentTargetLang())
        if (sourceCode == null || targetCode == null) return

        val key = "$sourceCode->$targetCode"
        if (standardModelDownloadReady[key] == true) return

        if (standardModelDownloadInFlight.putIfAbsent(key, true) != null) {
            if (showHint) {
                Toast.makeText(this, getString(R.string.standard_langpack_downloading), Toast.LENGTH_LONG).show()
            }
            return
        }

        if (showHint) {
            Toast.makeText(this, getString(R.string.standard_langpack_downloading), Toast.LENGTH_LONG).show()
        }

        standardModelPrefetchScope.launch {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceCode)
                .setTargetLanguage(targetCode)
                .build()
            val translator = Translation.getClient(options)
            try {
                val conditions = DownloadConditions.Builder().requireWifi().build()
                translator.downloadModelIfNeeded(conditions).await()
                standardModelDownloadReady[key] = true
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.standard_langpack_download_complete),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.standard_langpack_download_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                standardModelDownloadInFlight.remove(key)
                translator.close()
            }
        }
    }

    private fun mapLangCodeForLocalModel(lang: String): String? {
        return when (lang) {
            "Japanese" -> TranslateLanguage.JAPANESE
            "English" -> TranslateLanguage.ENGLISH
            "Chinese" -> TranslateLanguage.CHINESE
            "Korean" -> TranslateLanguage.KOREAN
            else -> null
        }
    }

    private fun resolveSavedLanguageCode(
        raw: String?,
        options: List<UiLanguageOption>,
        fallback: String
    ): String {
        if (raw.isNullOrBlank()) return fallback
        val normalized = raw.trim()

        options.firstOrNull { it.ocrCode.equals(normalized, ignoreCase = true) }?.let { return it.ocrCode }
        options.firstOrNull { it.label == normalized }?.let { return it.ocrCode }

        return fallback
    }

    private fun showStoreDialog() {
        val products = billingManager.productDetailsList.value

        if (products.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.main_store_not_ready_title)
                .setMessage(R.string.main_store_not_ready_message)
                .setPositiveButton(R.string.common_ok, null)
                .show()
            return
        }

        // [蹂寃? 媛寃⑹닚 ?뺣젹 (?ㅻ쾭 -> 怨⑤뱶 ?쒖꽌 ?좎?瑜??꾪빐 ?곹뭹 ID ?쒖슜 異붿쿇?섏?留??쇰떒 媛寃⑹닚)
        val sortedProducts = products.sortedBy { it.oneTimePurchaseOfferDetails?.priceAmountMicros ?: 0 }

        val productNames = sortedProducts.map {
            "${it.name} - ${it.oneTimePurchaseOfferDetails?.formattedPrice}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.main_recharge_dialog_title)
            .setItems(productNames) { _, which ->
                val selectedProduct = sortedProducts[which]
                billingManager.launchPurchaseFlow(this, selectedProduct)
            }
            .setNegativeButton(R.string.common_close, null)
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
                            // ?쒕퉬?ㅼ뿉??COMPLETE 諛⑹넚??蹂대궡誘濡??ш린??以묐났 泥섎━ 理쒖냼??
                            if(progressBar.visibility == View.VISIBLE) {
                                val message = state.data as? String
                                updateStatus(getString(R.string.main_status_done_format, message.orEmpty()), false)
                            }
                            btnStopWork.visibility = View.GONE
                            btnStopWork.isEnabled = true
                        }
                        is UiState.Error -> {
                            updateStatus(getString(R.string.main_status_error), false)
                            btnStopWork.visibility = View.GONE
                            showErrorDialog(state.message, state.onRetry)
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    updateNovelRunningStatusUi()
                    delay(1000)
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

        // [異붽?] 怨⑤뱶 ?붿븸 愿李?
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.userGoldBalance.collect { balance ->
                    if (auth.currentUser != null) {
                        tvGoldBalance.text = getString(R.string.main_gold_balance_format, balance)
                    }
                }
            }
        }

    }

    private fun showErrorDialog(message: String, onRetry: (() -> Unit)?) {
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.main_error_dialog_title)
            .setMessage(message)
            .setPositiveButton(R.string.common_ok, null)
        if (onRetry != null) {
            builder.setNegativeButton(R.string.common_retry) { _, _ -> onRetry.invoke() }
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
                Toast.makeText(this, getString(R.string.main_overlay_permission_required), Toast.LENGTH_LONG).show()
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
        val selectedLang = currentSourceLang()
        val selectedTargetLang = currentTargetLang()
        // [蹂寃? pendingAutoMode???곕씪 ACTION_START_AUTO / ACTION_START 遺꾧린
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
            tvTransTitle.text = getString(R.string.main_status_translating)
            ivTransIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_error))
            tvAutoTransTitle.text = getString(R.string.main_status_translating)
            ivAutoTransIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_error))
        } else {
            tvTransTitle.text = getString(R.string.action_screen_trans_instant)
            ivTransIcon.imageTintList = originalIconColor
            tvAutoTransTitle.text = getString(R.string.action_screen_trans_auto)
            ivAutoTransIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent_amber))
        }
    }

    private fun updateStatus(msg: String, isLoading: Boolean = false) {
        tvStatus.text = msg
        progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        btnSelectOverlay.isEnabled = !isLoading && (auth.currentUser != null)
        spinnerLang.isEnabled = !isLoading
        spinnerTargetLang.isEnabled = !isLoading
    }

    private fun updateNovelRunningStatusUi() {
        val novelRunning = TranslationWorkState.isNovelRunning(this)
        val massRunning = TranslationWorkState.isMassRunning(this)

        if (novelRunning && !massRunning) {
            tvStatus.visibility = View.VISIBLE
            tvStatus.text = getString(R.string.main_status_novel_running)
            progressBar.visibility = View.GONE
            btnStopWork.visibility = View.GONE
        } else if (!massRunning) {
            tvStatus.visibility = View.GONE
        }
    }

    private fun updateUI(isLoggedIn: Boolean) {
        if (isLoggedIn) {
            cardLogin.visibility = View.GONE
            cardSilverInfo.visibility = View.VISIBLE
            btnSelectOverlay.isEnabled = true
            findViewById<View>(R.id.btnScreenTransHelp).visibility = View.VISIBLE // [異붽?]
        } else {
            cardLogin.visibility = View.VISIBLE
            cardSilverInfo.visibility = View.GONE
            btnSelectOverlay.isEnabled = false
            findViewById<View>(R.id.btnScreenTransHelp).visibility = View.GONE // [異붽?]
        }
    }

    override fun onDestroy() {
        billingManager.endConnection()
        standardModelPrefetchScope.cancel()
        super.onDestroy()
    }
}




