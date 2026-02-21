package com.tuna.proj_01

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.math.floor

class NovelTranslationActivity : LocalizedActivity() {
    companion object {
        private const val PREF_APP = "app_prefs"
        private const val KEY_NOVEL_TARGET_LANG_CODE = "novel_target_lang_code"
    }

    private data class TargetLangOption(
        val label: String,
        val code: String
    )

    private lateinit var etInput: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var btnComplete: Button
    private lateinit var spinnerModelTier: MaterialAutoCompleteTextView
    private lateinit var spinnerTargetLang: MaterialAutoCompleteTextView
    private lateinit var btnNavMain: Button
    private lateinit var btnNavLibrary: Button
    private var selectedNovelTargetLangCode: String = "KO"

    private val modelLabels by lazy {
        listOf(
            getString(R.string.novel_mode_balanced_cost),
            getString(R.string.novel_mode_precise_cost)
        )
    }

    private val targetLangOptions by lazy {
        listOf(
            TargetLangOption(getString(R.string.vn_lang_korean), "KO"),
            TargetLangOption(getString(R.string.vn_lang_english), "EN"),
            TargetLangOption(getString(R.string.vn_lang_japanese), "JA"),
            TargetLangOption(getString(R.string.vn_lang_chinese), "ZH")
        )
    }

    private val txtPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        if (!isTxtFile(uri)) {
            showMessageDialog(R.string.novel_translation_txt_only)
            return@registerForActivityResult
        }

        val text = readTextFromUri(uri)
        if (text.isNullOrEmpty()) {
            showMessageDialog(R.string.novel_translation_empty_file)
            return@registerForActivityResult
        }
        etInput.setText(text)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_novel_translation)

        etInput = findViewById(R.id.et_novel_input)
        progressBar = findViewById(R.id.progress_translate)
        btnComplete = findViewById(R.id.btn_complete_novel)
        spinnerModelTier = findViewById(R.id.spinner_model_tier)
        spinnerTargetLang = findViewById(R.id.spinner_target_lang)
        btnNavMain = findViewById(R.id.btn_nav_main)
        btnNavLibrary = findViewById(R.id.btn_nav_library)

        setupDropdowns()

        findViewById<Button>(R.id.btn_pick_txt).setOnClickListener {
            txtPickerLauncher.launch("text/plain")
        }

        btnComplete.setOnClickListener {
            if (FirebaseAuth.getInstance().currentUser == null) {
                showMessageDialog(R.string.novel_translation_login_needed)
                return@setOnClickListener
            }

            val source = etInput.text.toString()
            if (source.isBlank()) {
                showMessageDialog(R.string.novel_translation_input_needed)
                return@setOnClickListener
            }

            if (source.length <= 100) {
                showMessageDialog(R.string.novel_translation_min_length)
                return@setOnClickListener
            }

            if (source.length > 20000) {
                showMessageDialog(R.string.novel_translation_max_length)
                return@setOnClickListener
            }

            val modelTier = selectedModelTier()
            val targetLangCode = selectedTargetLangCode()
            val requiredCoin = calculateRequiredSilver(source.length, modelTier)
            showCostDialog(source, targetLangCode, modelTier, requiredCoin)
        }

        btnNavMain.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
            finish()
        }

        btnNavLibrary.setOnClickListener {
            startActivity(Intent(this, LibraryActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
            finish()
        }
    }

    private fun setupDropdowns() {
        spinnerModelTier.setAdapter(ArrayAdapter(this, R.layout.item_dropdown_option, modelLabels))
        spinnerModelTier.setText(modelLabels.first(), false)

        val prefs = getSharedPreferences(PREF_APP, MODE_PRIVATE)
        val targetLabels = targetLangOptions.map { it.label }
        spinnerTargetLang.setAdapter(ArrayAdapter(this, R.layout.item_dropdown_option, targetLabels))
        selectedNovelTargetLangCode = resolveSavedTargetLangCode(
            raw = prefs.getString(KEY_NOVEL_TARGET_LANG_CODE, null),
            fallback = targetLangOptions.first().code
        )
        val initialTarget = targetLangOptions.firstOrNull { it.code == selectedNovelTargetLangCode }
            ?: targetLangOptions.first()
        spinnerTargetLang.setText(initialTarget.label, false)
        spinnerTargetLang.setOnItemClickListener { _, _, position, _ ->
            val selected = targetLangOptions.getOrNull(position)
                ?: targetLangOptions.firstOrNull { it.label == spinnerTargetLang.text?.toString().orEmpty() }
                ?: targetLangOptions.first()
            selectedNovelTargetLangCode = selected.code
            prefs.edit().putString(KEY_NOVEL_TARGET_LANG_CODE, selectedNovelTargetLangCode).apply()
        }
    }

    private fun showCostDialog(source: String, targetLangCode: String, modelTier: String, requiredCoin: Long) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.novel_translation_cost_title))
            .setMessage(
                getString(
                    R.string.novel_translation_cost_message,
                    source.length,
                    requiredCoin
                )
            )
            .setPositiveButton(getString(R.string.novel_translation_use_silver)) { _, _ ->
                startTranslationInBackground(source, targetLangCode, modelTier, requiredCoin)
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun startTranslationInBackground(
        source: String,
        targetLangCode: String,
        modelTier: String,
        requiredCoin: Long
    ) {
        if (TranslationWorkState.isAnyTranslationRunning(this)) {
            val runningTask = TranslationWorkState.runningTaskName(this)
                ?: getString(R.string.translation_running_other_task)
            showMessageDialog(getString(R.string.translation_running_block_message, runningTask))
            return
        }

        progressBar.visibility = View.VISIBLE
        btnComplete.isEnabled = false

        lifecycleScope.launch {
            val user = FirebaseAuth.getInstance().currentUser
            if (user == null) {
                progressBar.visibility = View.GONE
                btnComplete.isEnabled = true
                showMessageDialog(R.string.novel_translation_login_needed)
                return@launch
            }

            val currentSilver = try {
                val snapshot = FirebaseFirestore.getInstance()
                    .collection("users")
                    .document(user.uid)
                    .get()
                    .await()
                snapshot.getLong("current_balance") ?: 0L
            } catch (_: Exception) {
                null
            }

            if (currentSilver == null) {
                progressBar.visibility = View.GONE
                btnComplete.isEnabled = true
                showMessageDialog(R.string.novel_translation_balance_check_failed)
                return@launch
            }

            if (currentSilver < requiredCoin) {
                progressBar.visibility = View.GONE
                btnComplete.isEnabled = true
                showMessageDialog(getString(R.string.novel_translation_insufficient_silver_detail, requiredCoin, currentSilver))
                return@launch
            }

            val intent = Intent(this@NovelTranslationActivity, NovelTranslationService::class.java).apply {
                action = NovelTranslationService.ACTION_START
                putExtra(NovelTranslationService.EXTRA_TEXT, source)
                putExtra(NovelTranslationService.EXTRA_TARGET_LANG, targetLangCode)
                putExtra(NovelTranslationService.EXTRA_MODEL_TIER, modelTier)
            }

            ContextCompat.startForegroundService(this@NovelTranslationActivity, intent)
            showMessageDialog(R.string.novel_translation_started_background)
        }
    }

    private fun selectedModelTier(): String {
        val selectedIndex = modelLabels.indexOf(spinnerModelTier.text.toString())
        return if (selectedIndex == 1) "PRO" else "ADVANCED"
    }

    private fun selectedTargetLangCode(): String {
        return selectedNovelTargetLangCode
    }

    private fun calculateRequiredSilver(charCount: Int, modelTier: String): Long {
        val rate = if (modelTier == "PRO") 20.0 else 2.0
        return floor((charCount * rate) / 100.0).toLong()
    }

    private fun readTextFromUri(uri: Uri): String? {
        return try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }

    private fun isTxtFile(uri: Uri): Boolean {
        val mime = contentResolver.getType(uri)
        if (mime == "text/plain") return true

        val name = queryDisplayName(uri) ?: return false
        return name.lowercase().endsWith(".txt")
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveSavedTargetLangCode(raw: String?, fallback: String): String {
        if (raw.isNullOrBlank()) return fallback
        val normalized = raw.trim()
        targetLangOptions.firstOrNull { it.code.equals(normalized, ignoreCase = true) }?.let { return it.code }
        targetLangOptions.firstOrNull { it.label == normalized }?.let { return it.code }
        return fallback
    }
}




