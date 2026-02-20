package com.tuna.proj_01

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

class SettingsActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var historyAdapter: SilverHistoryAdapter

    private val exportPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            exportAllBooks()
        } else {
            Toast.makeText(this, getString(R.string.settings_storage_permission_required), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        initView()
        loadUserInfo()
        loadTransactionHistory()
    }

    private fun initView() {
        // 뒤로가기
        findViewById<LinearLayout>(R.id.btn_back).setOnClickListener { finish() }

        // 로그아웃
        findViewById<TextView>(R.id.btn_logout).setOnClickListener { showLogoutDialog() }

        // UID 복사
        findViewById<TextView>(R.id.btn_copy_uid).setOnClickListener {
            val uid = findViewById<TextView>(R.id.tv_user_uid).text.toString()
            copyToClipboard(uid)
        }

        findViewById<TextView>(R.id.btn_language).setOnClickListener { showLanguageDialog() }

        // 테마 설정
        findViewById<TextView>(R.id.btn_theme).setOnClickListener { showThemeDialog() }

        // 피드백
        findViewById<TextView>(R.id.btn_feedback).setOnClickListener { sendFeedbackEmail() }

        // [추가] 책 내보내기 버튼 연결
        findViewById<TextView>(R.id.btn_export_all_books).setOnClickListener {
            checkExportPermission()
        }

        findViewById<TextView>(R.id.btn_privacy_policy).setOnClickListener {
            // 실제 사용하실 주소로 변경해주세요
            val url = "https://sites.google.com/view/glass-beta/"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        }

        // 버전 정보
        val tvVersion = findViewById<TextView>(R.id.tv_version)
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            tvVersion.text = "v${pInfo.versionName}"
        } catch (e: Exception) {
            tvVersion.text = getString(R.string.common_unknown)
        }

        // RecyclerView 설정
        val rvHistory = findViewById<RecyclerView>(R.id.rv_silver_history)
        historyAdapter = SilverHistoryAdapter()
        rvHistory.layoutManager = LinearLayoutManager(this)
        rvHistory.adapter = historyAdapter
    }

    private fun loadUserInfo() {
        val user = auth.currentUser
        val tvEmail = findViewById<TextView>(R.id.tv_user_email)
        val tvUid = findViewById<TextView>(R.id.tv_user_uid)
        val tvSilver = findViewById<TextView>(R.id.tv_setting_silver)
        val tvGold = findViewById<TextView>(R.id.tv_setting_gold)

        if (user != null) {
            tvEmail.text = user.email ?: getString(R.string.settings_no_email)
            tvUid.text = user.uid

            // 잔액 실시간 업데이트
            db.collection("users").document(user.uid)
                .addSnapshotListener { snapshot, e ->
                    if (e != null) return@addSnapshotListener
                    if (snapshot != null && snapshot.exists()) {
                        val silver = snapshot.getLong("current_balance") ?: 0
                        val gold = snapshot.getLong("gold_balance") ?: 0
                        tvSilver.text = getString(R.string.settings_silver_format, silver)
                        tvGold.text = getString(R.string.settings_gold_format, gold)
                    }
                }
        } else {
            tvEmail.text = getString(R.string.settings_login_required)
            tvUid.text = "-"
            findViewById<TextView>(R.id.btn_copy_uid).visibility = View.GONE
            findViewById<TextView>(R.id.btn_logout).visibility = View.GONE
        }
    }

    private fun loadTransactionHistory() {
        val user = auth.currentUser ?: return
        val tvEmpty = findViewById<TextView>(R.id.tv_empty_history)

        db.collection("users").document(user.uid)
            .collection("transactions")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(50)
            .get()
            .addOnSuccessListener { result ->
                if (result.isEmpty) {
                    tvEmpty.visibility = View.VISIBLE
                    historyAdapter.submitList(emptyList())
                } else {
                    val list = result.documents.mapNotNull { doc ->
                        try {
                            val type = doc.getString("type") ?: ""
                            val currency = doc.getString("currency") ?: "Silver"
                            val amount = doc.getLong("amount") ?: 0L
                            val desc = doc.getString("description") ?: getString(R.string.settings_history_default)

                            val displayDesc = "[$currency] $desc"

                            SilverHistory(
                                id = doc.id,
                                amount = amount,
                                description = displayDesc,
                                timestamp = doc.getDate("timestamp")?.time ?: System.currentTimeMillis()
                            )
                        } catch (e: Exception) { null }
                    }
                    tvEmpty.visibility = View.GONE
                    historyAdapter.submitList(list)
                }
            }
            .addOnFailureListener {
                tvEmpty.text = getString(R.string.settings_history_load_failed)
                tvEmpty.visibility = View.VISIBLE
            }
    }

    private fun checkExportPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                exportPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return
            }
        }
        exportAllBooks()
    }

    private fun exportAllBooks() {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings_exporting_title)
            .setMessage(R.string.settings_exporting_message)
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch(Dispatchers.IO) {
            val rootDir = File(filesDir, "Books")
            if (!rootDir.exists()) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@SettingsActivity, getString(R.string.settings_no_books_to_export), Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val books = rootDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
            var successCount = 0

            for (bookFolder in books) {
                // 책 제목 찾기
                val jsonFile = File(bookFolder, "metadata.json")
                var title = bookFolder.name
                if (jsonFile.exists()) {
                    try {
                        val json = JSONObject(jsonFile.readText())
                        if (json.has("title")) title = json.getString("title")
                    } catch (e: Exception) {}
                }

                // 폴더명으로 안전한 이름 만들기
                val safeTitle = title.replace(Regex("[^a-zA-Z0-9가-힣_\\- ]"), "").trim()
                val targetName = if (safeTitle.isNotEmpty()) safeTitle else bookFolder.name

                // 1. 소설 확인 (translated.txt)
                val translatedFile = File(bookFolder, "translated.txt")
                if (translatedFile.exists()) {
                    val exportName = "$targetName.txt"
                    ExportHelper.saveFileToDownloads(
                        this@SettingsActivity,
                        translatedFile,
                        "Novels",
                        exportName,
                        "text/plain"
                    )
                    successCount++
                    continue
                }

                // 2. 만화 이미지 확인
                val images = bookFolder.listFiles { f ->
                    val n = f.name.lowercase()
                    n.endsWith(".jpg") || n.endsWith(".png")
                }?.sortedBy { it.name }

                if (images.isNullOrEmpty()) continue

                for (image in images) {
                    ExportHelper.saveFileToDownloads(
                        this@SettingsActivity,
                        image,
                        "Manga/$targetName",
                        image.name,
                        "image/jpeg"
                    )
                }
                successCount++
            }

            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                if (successCount > 0) {
                    Toast.makeText(this@SettingsActivity, getString(R.string.settings_export_success_format, successCount), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@SettingsActivity, getString(R.string.settings_no_images_to_export), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showLogoutDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.common_logout)
            .setMessage(R.string.settings_logout_confirm_message)
            .setPositiveButton(R.string.common_logout) { _, _ ->
                auth.signOut()

                val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
                val googleSignInClient = GoogleSignIn.getClient(this, gso)
                googleSignInClient.signOut().addOnCompleteListener {
                    Toast.makeText(this, getString(R.string.settings_logged_out), Toast.LENGTH_SHORT).show()
                    val intent = Intent(this, MainActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun showThemeDialog() {
        val themes = resources.getStringArray(R.array.theme_options)
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_theme)
            .setItems(themes) { _, which ->
                when (which) {
                    0 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                    1 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                    2 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun showLanguageDialog() {
        val languageOptions = resources.getStringArray(R.array.app_language_options)
        val languageTags = arrayOf("ko", "ja", "zh", "en")
        val currentTag = AppCompatDelegate.getApplicationLocales().toLanguageTags().ifBlank { "ko" }
        val checked = languageTags.indexOfFirst { currentTag.startsWith(it) }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_language)
            .setSingleChoiceItems(languageOptions, checked) { dialog, which ->
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTags[which]))
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun sendFeedbackEmail() {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf("developer@example.com"))
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.settings_feedback_subject))
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.settings_no_email_app), Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("UID", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, getString(R.string.settings_uid_copied), Toast.LENGTH_SHORT).show()
    }
}
