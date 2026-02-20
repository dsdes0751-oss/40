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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.firebase.auth.FirebaseAuth
import kotlin.math.floor

class NovelTranslationActivity : AppCompatActivity() {

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
            Toast.makeText(this, getString(R.string.novel_translation_txt_only), Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }

        val text = readTextFromUri(uri)
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, getString(R.string.novel_translation_empty_file), Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this, getString(R.string.novel_translation_login_needed), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val source = etInput.text.toString()
            if (source.isBlank()) {
                Toast.makeText(this, getString(R.string.novel_translation_input_needed), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (source.length <= 100) {
                Toast.makeText(this, getString(R.string.novel_translation_min_length), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (source.length > 20000) {
                Toast.makeText(this, getString(R.string.novel_translation_max_length), Toast.LENGTH_SHORT).show()
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

        val targetLabels = targetLangOptions.map { it.label }
        spinnerTargetLang.setAdapter(ArrayAdapter(this, R.layout.item_dropdown_option, targetLabels))
        spinnerTargetLang.setText(targetLabels.first(), false)
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
                startTranslationInBackground(source, targetLangCode, modelTier)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startTranslationInBackground(
        source: String,
        targetLangCode: String,
        modelTier: String
    ) {
        if (TranslationWorkState.isAnyTranslationRunning(this)) {
            val runningTask = TranslationWorkState.runningTaskName(this) ?: "?ㅻⅨ 踰덉뿭"
            Toast.makeText(this, getString(R.string.translation_running_block_message, runningTask), Toast.LENGTH_LONG).show()
            return
        }

        progressBar.visibility = View.VISIBLE
        btnComplete.isEnabled = false

        val intent = Intent(this, NovelTranslationService::class.java).apply {
            action = NovelTranslationService.ACTION_START
            putExtra(NovelTranslationService.EXTRA_TEXT, source)
            putExtra(NovelTranslationService.EXTRA_TARGET_LANG, targetLangCode)
            putExtra(NovelTranslationService.EXTRA_MODEL_TIER, modelTier)
        }

        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, getString(R.string.novel_translation_started_background), Toast.LENGTH_LONG).show()
    }

    private fun selectedModelTier(): String {
        val selectedIndex = modelLabels.indexOf(spinnerModelTier.text.toString())
        return if (selectedIndex == 1) "PRO" else "ADVANCED"
    }

    private fun selectedTargetLangCode(): String {
        val selected = spinnerTargetLang.text.toString()
        return targetLangOptions.firstOrNull { it.label == selected }?.code ?: "KO"
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
}
