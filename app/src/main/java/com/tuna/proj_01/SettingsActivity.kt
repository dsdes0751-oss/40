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
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
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
import kotlin.math.abs

class SettingsActivity : LocalizedActivity() {
    companion object {
        // ?댁뿭? 理쒓렐 50媛쒕쭔 ?쒖떆?섏뿬 ?뚮뜑留??ㅽ겕濡?鍮꾩슜???쒗븳
        private const val MAX_HISTORY_ITEMS = 50L
        const val EXTRA_LANGUAGE_CHANGED = "extra_language_changed"
    }

    private data class TransactionEntry(
        val id: String,
        val type: String,
        val currency: String,
        val amount: Long,
        val description: String,
        val timestamp: Long
    )

    private data class ParsedMassUse(
        val pages: Int,
        val tier: String
    )

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var historyAdapter: SilverHistoryAdapter
    private lateinit var tvLanguageCurrent: TextView

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
        // ?ㅻ줈媛湲?
        findViewById<LinearLayout>(R.id.btn_back).setOnClickListener { finish() }

        // 濡쒓렇?꾩썐
        findViewById<TextView>(R.id.btn_logout).setOnClickListener { showLogoutDialog() }

        // UID 蹂듭궗
        findViewById<TextView>(R.id.btn_copy_uid).setOnClickListener {
            val uid = findViewById<TextView>(R.id.tv_user_uid).text.toString()
            copyToClipboard(uid)
        }

        tvLanguageCurrent = findViewById(R.id.tv_language_current)
        updateLanguageRow()
        findViewById<View>(R.id.btn_language).setOnClickListener { showLanguageDialog() }

        // ?뚮쭏 ?ㅼ젙
        findViewById<TextView>(R.id.btn_theme).setOnClickListener { showThemeDialog() }

        // ?쇰뱶諛?
        findViewById<TextView>(R.id.btn_feedback).setOnClickListener { sendFeedbackEmail() }

        // [異붽?] 梨??대낫?닿린 踰꾪듉 ?곌껐
        findViewById<TextView>(R.id.btn_export_all_books).setOnClickListener {
            checkExportPermission()
        }

        findViewById<TextView>(R.id.btn_privacy_policy).setOnClickListener {
            // ?ㅼ젣 ?ъ슜?섏떎 二쇱냼濡?蹂寃쏀빐二쇱꽭??
            val url = "https://sites.google.com/view/glass-beta/"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        }

        // 踰꾩쟾 ?뺣낫
        val tvVersion = findViewById<TextView>(R.id.tv_version)
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            tvVersion.text = getString(R.string.settings_version_name_format, pInfo.versionName)
        } catch (e: Exception) {
            tvVersion.text = getString(R.string.common_unknown)
        }

        // RecyclerView ?ㅼ젙
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

            // ?붿븸 ?ㅼ떆媛??낅뜲?댄듃
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
            .limit(MAX_HISTORY_ITEMS)
            .get()
            .addOnSuccessListener { result ->
                if (result.isEmpty) {
                    tvEmpty.visibility = View.VISIBLE
                    historyAdapter.submitList(emptyList())
                } else {
                    val entries = result.documents.mapNotNull { doc ->
                        try {
                            TransactionEntry(
                                id = doc.id,
                                type = doc.getString("type").orEmpty(),
                                currency = doc.getString("currency") ?: "Silver",
                                amount = doc.getLong("amount") ?: 0L,
                                description = doc.getString("description") ?: "",
                                timestamp = doc.getDate("timestamp")?.time ?: System.currentTimeMillis()
                            )
                        } catch (_: Exception) {
                            null
                        }
                    }

                    val list = buildHistoryRows(entries)
                    tvEmpty.visibility = View.GONE
                    historyAdapter.submitList(list)
                }
            }
            .addOnFailureListener {
                tvEmpty.text = getString(R.string.settings_history_load_failed)
                tvEmpty.visibility = View.VISIBLE
            }
    }

    private fun buildHistoryRows(entries: List<TransactionEntry>): List<SilverHistory> {
        if (entries.isEmpty()) return emptyList()

        val rows = mutableListOf<SilverHistory>()
        var i = 0

        while (i < entries.size) {
            val entry = entries[i]
            val mass = parseMassUse(entry)

            if (mass != null) {
                var j = i
                var lastTimestamp = entry.timestamp
                var sumAmount = 0L
                var sumPages = 0
                var interrupted = false

                while (j < entries.size) {
                    val current = entries[j]
                    val currentMass = parseMassUse(current) ?: break
                    if (currentMass.tier != mass.tier || current.currency != entry.currency) break
                    if (j > i && abs(lastTimestamp - current.timestamp) > 10 * 60 * 1000L) break

                    sumAmount += current.amount
                    sumPages += currentMass.pages
                    lastTimestamp = current.timestamp
                    j++
                }

                val maybeStatus = entries.getOrNull(j)
                if (maybeStatus != null && maybeStatus.type == "MASS_SESSION_INTERRUPTED") {
                    interrupted = true
                    j += 1
                }

                val modeLabel = tierToModeLabel(mass.tier)
                val currencyLabel = currencyToLabel(entry.currency, mass.tier)
                val baseSubtitle = getString(
                    R.string.settings_history_bulk_subtitle_format,
                    modeLabel,
                    currencyLabel,
                    sumPages
                )
                val subtitle = if (interrupted) {
                    baseSubtitle + getString(R.string.settings_history_bulk_interrupted_suffix)
                } else {
                    baseSubtitle
                }

                rows += SilverHistory(
                    id = "mass_${entry.id}",
                    title = getString(R.string.settings_history_type_manga_batch),
                    subtitle = subtitle,
                    timestamp = entry.timestamp,
                    amountText = if (interrupted) {
                        getString(R.string.settings_history_amount_status_stopped)
                    } else {
                        formatAmount(sumAmount, entry.currency)
                    },
                    amountKind = if (interrupted) AmountKind.NEUTRAL else amountKindFromAmount(sumAmount)
                )
                i = j
                continue
            }

            rows += mapSingleEntry(entry)
            i++
        }

        return rows
    }

    private fun mapSingleEntry(entry: TransactionEntry): SilverHistory {
        val desc = entry.description
        val currencyLabel = currencyToLabel(entry.currency, extractTier(desc))

        if (entry.type == "MASS_SESSION_INTERRUPTED") {
            val interruptedRegex = Regex(".*\\((\\d+)/(\\d+) pages\\) \\[([A-Z]+)]")
            val match = interruptedRegex.find(desc)
            val done = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            val total = match?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
            val mode = tierToModeLabel(match?.groupValues?.getOrNull(3).orEmpty())
            val subtitle = if (total > 0) {
                getString(R.string.settings_history_bulk_subtitle_format, mode, currencyLabel, total) +
                        getString(R.string.settings_history_bulk_interrupted_suffix)
            } else {
                getString(R.string.settings_history_subtitle_format, mode, currencyLabel) +
                        getString(R.string.settings_history_bulk_interrupted_suffix)
            }
            return SilverHistory(
                id = entry.id,
                title = getString(R.string.settings_history_type_manga_batch),
                subtitle = subtitle,
                timestamp = entry.timestamp,
                amountText = getString(R.string.settings_history_amount_status_stopped),
                amountKind = AmountKind.NEUTRAL
            )
        }

        if (entry.type == "USE_SILVER_FOR_VN_TOPUP" || desc.startsWith("VN top-up")) {
            val pkgMatch = Regex("VN top-up\\s+([A-Z]+)\\s+\\(\\+(\\d+) chars\\)").find(desc)
            val packageId = pkgMatch?.groupValues?.getOrNull(1).orEmpty()
            val charCount = pkgMatch?.groupValues?.getOrNull(2)?.toIntOrNull()
            val pkgLabel = when {
                charCount != null -> getString(R.string.settings_history_chars_count_format, charCount)
                packageId == "SMALL" -> getString(R.string.settings_history_chars_5000)
                packageId == "MEDIUM" -> getString(R.string.settings_history_chars_10000)
                packageId == "LARGE" -> getString(R.string.settings_history_chars_50000)
                else -> "-"
            }
            val subtitle = getString(R.string.settings_history_package_subtitle_format, pkgLabel, currencyLabel)
            return SilverHistory(
                id = entry.id,
                title = getString(R.string.settings_history_type_vn_topup),
                subtitle = subtitle,
                timestamp = entry.timestamp,
                amountText = formatAmount(entry.amount, entry.currency),
                amountKind = amountKindFromAmount(entry.amount)
            )
        }

        if (entry.type == "USE_VN_CHARS") {
            val subtitle = getString(R.string.settings_history_chars_subtitle_format, abs(entry.amount), currencyLabel)
            return SilverHistory(
                id = entry.id,
                title = getString(R.string.settings_history_type_vn_fast),
                subtitle = subtitle,
                timestamp = entry.timestamp,
                amountText = formatAmount(entry.amount, entry.currency),
                amountKind = amountKindFromAmount(entry.amount)
            )
        }

        if (entry.type == "CHARGE") {
            val subtitle = getString(R.string.settings_history_subtitle_format, "-", currencyLabel)
            return SilverHistory(
                id = entry.id,
                title = getString(R.string.settings_history_type_charge),
                subtitle = subtitle,
                timestamp = entry.timestamp,
                amountText = formatAmount(entry.amount, entry.currency),
                amountKind = amountKindFromAmount(entry.amount)
            )
        }

        if (entry.type == "REFUND") {
            val subtitle = getString(R.string.settings_history_subtitle_format, "-", currencyLabel)
            return SilverHistory(
                id = entry.id,
                title = getString(R.string.settings_history_type_refund),
                subtitle = subtitle,
                timestamp = entry.timestamp,
                amountText = formatAmount(entry.amount, entry.currency),
                amountKind = amountKindFromAmount(entry.amount)
            )
        }

        val screenMatch = Regex("Screen Translation(?: \\(([^)]+)\\))? \\[([A-Z]+)]").find(desc)
        if (screenMatch != null) {
            val modeSource = screenMatch.groupValues.getOrNull(1).orEmpty()
            val modeTitle = when (modeSource.uppercase()) {
                "AUTO" -> getString(R.string.settings_history_type_screen_auto)
                "INSTANT", "MANUAL" -> getString(R.string.settings_history_type_screen_manual)
                else -> getString(R.string.settings_history_type_screen)
            }
            val mode = tierToModeLabel(screenMatch.groupValues.getOrNull(2).orEmpty())
            val subtitle = getString(R.string.settings_history_subtitle_format, mode, currencyLabel)
            return SilverHistory(
                id = entry.id,
                title = modeTitle,
                subtitle = subtitle,
                timestamp = entry.timestamp,
                amountText = formatAmount(entry.amount, entry.currency),
                amountKind = amountKindFromAmount(entry.amount)
            )
        }

        val novelMatch = Regex("Novel Translation \\((\\d+) chars, ([^)]+)\\)").find(desc)
        if (novelMatch != null) {
            val modelName = novelMatch.groupValues.getOrNull(2).orEmpty().lowercase()
            val mode = if (modelName.contains("pro")) getString(R.string.model_mode_precise) else getString(R.string.model_mode_balanced)
            val subtitle = getString(R.string.settings_history_subtitle_format, mode, currencyLabel)
            return SilverHistory(
                id = entry.id,
                title = getString(R.string.settings_history_type_novel),
                subtitle = subtitle,
                timestamp = entry.timestamp,
                amountText = formatAmount(entry.amount, entry.currency),
                amountKind = amountKindFromAmount(entry.amount)
            )
        }

        val subtitle = getString(R.string.settings_history_subtitle_format, "-", currencyLabel)
        return SilverHistory(
            id = entry.id,
            title = if (desc.isNotBlank()) desc else getString(R.string.settings_history_type_unknown),
            subtitle = subtitle,
            timestamp = entry.timestamp,
            amountText = formatAmount(entry.amount, entry.currency),
            amountKind = if (entry.amount == 0L) AmountKind.NEUTRAL else amountKindFromAmount(entry.amount)
        )
    }

    private fun parseMassUse(entry: TransactionEntry): ParsedMassUse? {
        val match = Regex("Manga(?: Batch)? Translation \\((\\d+) pages\\) \\[([A-Z]+)]").find(entry.description)
            ?: return null
        val pages = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val tier = match.groupValues.getOrNull(2).orEmpty()
        return ParsedMassUse(pages = pages, tier = tier)
    }

    private fun extractTier(description: String): String {
        return Regex("\\[([A-Z]+)]").find(description)?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun tierToModeLabel(tier: String): String {
        return when (tier.uppercase()) {
            "STANDARD" -> getString(R.string.model_mode_standard)
            "ADVANCED" -> getString(R.string.model_mode_balanced)
            "PRO" -> getString(R.string.model_mode_precise)
            else -> "-"
        }
    }

    private fun currencyToLabel(currency: String, tier: String = ""): String {
        return when (currency.uppercase()) {
            "SILVER" -> getString(R.string.settings_history_currency_silver)
            "GOLD" -> getString(R.string.settings_history_currency_gold)
            "VN_CHARS" -> getString(R.string.settings_history_currency_vn_chars)
            else -> {
                if (tier.uppercase() == "STANDARD") getString(R.string.settings_history_currency_free)
                else currency
            }
        }
    }

    private fun formatAmount(amount: Long, currency: String): String {
        if (amount == 0L) return getString(R.string.settings_history_amount_status_info)
        return when (currency.uppercase()) {
            "GOLD" -> getString(R.string.settings_history_amount_gold_format, amount)
            "VN_CHARS" -> getString(R.string.settings_history_amount_chars_format, amount)
            else -> getString(R.string.settings_history_amount_silver_format, amount)
        }
    }

    private fun amountKindFromAmount(amount: Long): AmountKind {
        return when {
            amount > 0 -> AmountKind.POSITIVE
            amount < 0 -> AmountKind.NEGATIVE
            else -> AmountKind.NEUTRAL
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
                // 梨??쒕ぉ 李얘린
                val jsonFile = File(bookFolder, "metadata.json")
                var title = bookFolder.name
                if (jsonFile.exists()) {
                    try {
                        val json = JSONObject(jsonFile.readText())
                        if (json.has("title")) title = json.getString("title")
                    } catch (e: Exception) {}
                }

                // ?대뜑紐낆쑝濡??덉쟾???대쫫 留뚮뱾湲?
                val safeTitle = title.replace(Regex("[^a-zA-Z0-9媛-??\\- ]"), "").trim()
                val targetName = if (safeTitle.isNotEmpty()) safeTitle else bookFolder.name

                // 1. ?뚯꽕 ?뺤씤 (translated.txt)
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

                // 2. 留뚰솕 ?대?吏 ?뺤씤
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
        val themeModes = intArrayOf(
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            AppCompatDelegate.MODE_NIGHT_NO,
            AppCompatDelegate.MODE_NIGHT_YES
        )
        val currentMode = AppThemeManager.getThemeMode(this)
        val checked = themeModes.indexOf(currentMode).takeIf { it >= 0 } ?: 0

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_theme)
            .setSingleChoiceItems(themes, checked) { dialog, which ->
                AppThemeManager.setThemeMode(this, themeModes[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun showLanguageDialog() {
        val languageOptions = resources.getStringArray(R.array.app_language_options)
        val languageTags = AppLanguageManager.getSupportedLanguageTags()
        val currentTag = AppLanguageManager.getSelectedLanguageTag(this)
        val checked = languageTags.indexOfFirst { currentTag.startsWith(it) }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_language)
            .setSingleChoiceItems(languageOptions, checked) { dialog, which ->
                val selectedTag = languageTags[which]
                val changed = AppLanguageManager.setLanguage(this, selectedTag, applyNow = false)
                dialog.dismiss()
                if (changed) {
                    showRestartRequiredDialog(selectedTag)
                } else {
                    updateLanguageRow()
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun showRestartRequiredDialog(languageTag: String) {
        val localizedContext = AppLanguageManager.createLocalizedContext(this, languageTag)
        val localizedResources = localizedContext.resources

        AlertDialog.Builder(this)
            .setTitle(localizedResources.getString(R.string.settings_language_restart_required_title))
            .setMessage(localizedResources.getString(R.string.settings_language_restart_required_message))
            .setCancelable(false)
            .setPositiveButton(localizedResources.getString(R.string.common_ok)) { _, _ ->
                setResult(RESULT_OK, android.content.Intent().putExtra(EXTRA_LANGUAGE_CHANGED, true))
                finish()
            }
            .show()
    }

    private fun updateLanguageRow() {
        val currentTag = AppLanguageManager.getSelectedLanguageTag(this)
        tvLanguageCurrent.text = when {
            currentTag.startsWith("ko") -> getString(R.string.language_name_korean)
            currentTag.startsWith("ja") -> getString(R.string.language_name_japanese)
            else -> getString(R.string.language_name_english)
        }
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


