package com.tuna.proj_01

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

class NovelViewerActivity : AppCompatActivity() {

    private var exportText: String = ""
    private var exportFileName: String = "novel_translation.txt"
    private lateinit var translatedFile: File

    private val exportPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            exportTxt()
        } else {
            Toast.makeText(this, getString(R.string.settings_storage_permission_required), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_novel_viewer)

        val folderPath = intent.getStringExtra("book_folder_path")
        if (folderPath.isNullOrBlank()) {
            finish()
            return
        }

        val folder = File(folderPath)
        translatedFile = File(folder, "translated.txt")

        val title = findViewById<TextView>(R.id.tv_novel_title)
        val content = findViewById<TextView>(R.id.tv_novel_content)

        title.text = folder.name
        exportFileName = "${folder.name}.txt"

        if (!translatedFile.exists()) {
            content.text = getString(R.string.novel_translation_empty_file)
        } else {
            exportText = translatedFile.readText()
            content.text = exportText
        }

        findViewById<ImageButton>(R.id.btn_close).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btn_novel_settings).setOnClickListener {
            showSettingsDialog()
        }
    }

    private fun showSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.novel_viewer_settings_title))
            .setItems(arrayOf(getString(R.string.novel_viewer_export_txt))) { _, _ ->
                checkExportPermission()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun checkExportPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                exportPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return
            }
        }
        exportTxt()
    }

    private fun exportTxt() {
        if (exportText.isBlank() || !translatedFile.exists()) {
            Toast.makeText(this, getString(R.string.novel_translation_empty_file), Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val success = ExportHelper.saveFileToDownloads(
                this@NovelViewerActivity,
                translatedFile,
                "Novels",
                exportFileName,
                "text/plain"
            )

            if (success) {
                Toast.makeText(this@NovelViewerActivity, getString(R.string.novel_viewer_export_done), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@NovelViewerActivity, getString(R.string.novel_viewer_export_fail), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
