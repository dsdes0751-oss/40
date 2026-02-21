package com.tuna.proj_01

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

class VnGameTranslationActivity : LocalizedActivity() {
    companion object {
        private const val PREF_APP = "app_prefs"
        private const val KEY_VN_SOURCE_LANG_CODE = "vn_source_lang_code"
        private const val KEY_VN_TARGET_LANG_CODE = "vn_target_lang_code"
    }

    private data class VnLangOption(
        val label: String,
        val ocrLang: String,
        val deeplCode: String
    )

    private lateinit var spinnerSourceLang: MaterialAutoCompleteTextView
    private lateinit var spinnerTargetLang: MaterialAutoCompleteTextView
    private lateinit var progressBalance: ProgressBar
    private lateinit var tvPercent: TextView
    private lateinit var tvRemainingChars: TextView
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var selectedSourceLangCode: String = "Japanese"
    private var selectedTargetLangCode: String = "Korean"

    private val sourceLangOptions by lazy {
        listOf(
            VnLangOption(getString(R.string.vn_lang_japanese), "Japanese", "JA"),
            VnLangOption(getString(R.string.vn_lang_english), "English", "EN"),
            VnLangOption(getString(R.string.vn_lang_korean), "Korean", "KO"),
            VnLangOption(getString(R.string.vn_lang_chinese), "Chinese", "ZH")
        )
    }

    private val targetLangOptions by lazy {
        listOf(
            VnLangOption(getString(R.string.vn_lang_korean), "Korean", "KO"),
            VnLangOption(getString(R.string.vn_lang_english), "English", "EN"),
            VnLangOption(getString(R.string.vn_lang_japanese), "Japanese", "JA"),
            VnLangOption(getString(R.string.vn_lang_chinese), "Chinese", "ZH")
        )
    }

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
                requestScreenCapturePermission()
            } else {
                Toast.makeText(this, getString(R.string.vn_overlay_permission_required), Toast.LENGTH_SHORT).show()
            }
        }

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                startVnFastService(result.resultCode, result.data!!)
            } else {
                Toast.makeText(this, getString(R.string.vn_screen_capture_permission_denied), Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vn_game_translation)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        spinnerSourceLang = findViewById(R.id.spinner_vn_source_lang)
        spinnerTargetLang = findViewById(R.id.spinner_vn_target_lang)
        progressBalance = findViewById(R.id.progress_vn_balance)
        tvPercent = findViewById(R.id.tv_vn_percent)
        tvRemainingChars = findViewById(R.id.tv_vn_remaining_chars)

        setupDropdowns()
        updateBalanceUi(0L)

        findViewById<Button>(R.id.btn_nav_main).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
            finish()
        }

        findViewById<Button>(R.id.btn_nav_library).setOnClickListener {
            startActivity(Intent(this, LibraryActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
            finish()
        }

        findViewById<Button>(R.id.btn_vn_top_up).setOnClickListener {
            startActivity(Intent(this, VnCreditChargeActivity::class.java))
        }

        findViewById<Button>(R.id.btn_vn_start_translation).setOnClickListener {
            if (FirebaseAuth.getInstance().currentUser == null) {
                Toast.makeText(this, getString(R.string.novel_translation_login_needed), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (TranslationWorkState.isAnyTranslationRunning(this)) {
                val runningTask = TranslationWorkState.runningTaskName(this)
                    ?: getString(R.string.translation_running_other_task)
                Toast.makeText(this, getString(R.string.translation_running_block_message, runningTask), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            checkOverlayPermission()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshBalance()
    }

    private fun setupDropdowns() {
        val prefs = getSharedPreferences(PREF_APP, Context.MODE_PRIVATE)
        val sourceLabels = sourceLangOptions.map { it.label }
        spinnerSourceLang.setAdapter(ArrayAdapter(this, R.layout.item_dropdown_option, sourceLabels))
        val targetLabels = targetLangOptions.map { it.label }
        spinnerTargetLang.setAdapter(ArrayAdapter(this, R.layout.item_dropdown_option, targetLabels))

        selectedSourceLangCode = resolveSavedLangCode(
            raw = prefs.getString(KEY_VN_SOURCE_LANG_CODE, null),
            options = sourceLangOptions,
            fallback = sourceLangOptions.first().ocrLang
        )
        selectedTargetLangCode = resolveSavedLangCode(
            raw = prefs.getString(KEY_VN_TARGET_LANG_CODE, null),
            options = targetLangOptions,
            fallback = targetLangOptions.first().ocrLang
        )

        val initialSource = sourceLangOptions.firstOrNull { it.ocrLang == selectedSourceLangCode }
            ?: sourceLangOptions.first()
        val initialTarget = targetLangOptions.firstOrNull { it.ocrLang == selectedTargetLangCode }
            ?: targetLangOptions.first()
        spinnerSourceLang.setText(initialSource.label, false)
        spinnerTargetLang.setText(initialTarget.label, false)

        spinnerSourceLang.setOnItemClickListener { _, _, position, _ ->
            val selected = sourceLangOptions.getOrNull(position)
                ?: sourceLangOptions.firstOrNull { it.label == spinnerSourceLang.text?.toString().orEmpty() }
                ?: sourceLangOptions.first()
            selectedSourceLangCode = selected.ocrLang
            prefs.edit().putString(KEY_VN_SOURCE_LANG_CODE, selectedSourceLangCode).apply()
        }

        spinnerTargetLang.setOnItemClickListener { _, _, position, _ ->
            val selected = targetLangOptions.getOrNull(position)
                ?: targetLangOptions.firstOrNull { it.label == spinnerTargetLang.text?.toString().orEmpty() }
                ?: targetLangOptions.first()
            selectedTargetLangCode = selected.ocrLang
            prefs.edit().putString(KEY_VN_TARGET_LANG_CODE, selectedTargetLangCode).apply()
        }
    }

    private fun refreshBalance() {
        lifecycleScope.launch {
            try {
                val balance = VnCreditsRepository.getVnCharBalance()
                updateBalanceUi(balance)
            } catch (_: Exception) {
                updateBalanceUi(0L)
            }
        }
    }

    private fun updateBalanceUi(balanceRaw: Long) {
        val clamped = balanceRaw.coerceIn(0L, 100000L)
        val percent = ((clamped * 100L) / 100000L).toInt()
        progressBalance.max = 100000
        progressBalance.progress = clamped.toInt()
        tvPercent.text = getString(R.string.vn_percent_format, percent)
        tvRemainingChars.text = getString(R.string.vn_remaining_chars_format, clamped)
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = android.net.Uri.parse("package:$packageName")
            }
            overlayPermissionLauncher.launch(intent)
            return
        }
        requestScreenCapturePermission()
    }

    private fun requestScreenCapturePermission() {
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(captureIntent)
    }

    private fun startVnFastService(resultCode: Int, data: Intent) {
        val sourceOption = sourceLangOptions.firstOrNull { it.ocrLang == selectedSourceLangCode }
            ?: sourceLangOptions.first()
        val targetOption = targetLangOptions.firstOrNull { it.ocrLang == selectedTargetLangCode }
            ?: targetLangOptions.first()

        val intent = Intent(this, ScreenTranslationService::class.java).apply {
            action = ScreenTranslationService.ACTION_START_AUTO
            putExtra("resultCode", resultCode)
            putExtra("data", data)
            putExtra("sourceLang", sourceOption.ocrLang)
            putExtra("targetLang", targetOption.ocrLang)
            putExtra(ScreenTranslationService.EXTRA_TRANSLATION_MODE, ScreenTranslationService.MODE_VN_FAST)
            putExtra(ScreenTranslationService.EXTRA_VN_TARGET_LANG, targetOption.deeplCode)
            putExtra(ScreenTranslationService.EXTRA_VN_SOURCE_LANG, sourceOption.deeplCode)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        moveTaskToBack(true)
    }

    private fun resolveSavedLangCode(
        raw: String?,
        options: List<VnLangOption>,
        fallback: String
    ): String {
        if (raw.isNullOrBlank()) return fallback
        val normalized = raw.trim()
        options.firstOrNull { it.ocrLang.equals(normalized, ignoreCase = true) }?.let { return it.ocrLang }
        options.firstOrNull { it.label == normalized }?.let { return it.ocrLang }
        return fallback
    }
}




