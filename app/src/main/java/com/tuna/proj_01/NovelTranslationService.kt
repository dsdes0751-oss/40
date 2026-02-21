package com.tuna.proj_01

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NovelTranslationService : Service() {

    companion object {
        private const val TAG = "NovelTranslationService"
        const val ACTION_START = "com.tuna.proj_01.action.NOVEL_TRANSLATION_START"
        const val CHANNEL_ID = "NovelTranslationChannel"
        const val NOTIFICATION_ID = 1201

        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_TARGET_LANG = "extra_target_lang"
        const val EXTRA_MODEL_TIER = "extra_model_tier"
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (TranslationWorkState.isMassRunning(this) || TranslationWorkState.isNovelRunning(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        val text = intent.getStringExtra(EXTRA_TEXT)?.trim().orEmpty()
        val targetLang = intent.getStringExtra(EXTRA_TARGET_LANG).orEmpty().ifBlank { "KO" }
        val modelTier = intent.getStringExtra(EXTRA_MODEL_TIER).orEmpty().ifBlank { "ADVANCED" }

        if (text.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        TranslationWorkState.setNovelRunning(this, true)

        startForeground(
            NOTIFICATION_ID,
            createNotification(getString(R.string.novel_translation_notification_running))
        )

        serviceScope.launch {
            try {
                val (translatedText, cost) = NovelTranslationRepository.translateTxt(
                    text = text,
                    targetLang = targetLang,
                    modelTier = modelTier
                )

                val titleTimestamp = resolveNovelServerTimestampMillis(modelTier)
                saveNovelToBookshelf(
                    originalText = text,
                    translatedText = translatedText,
                    modelTier = modelTier,
                    titleTimestampMillis = titleTimestamp
                )

                updateNotification(
                    getString(R.string.novel_translation_notification_done, cost)
                )
                sendBroadcast(Intent(MassTranslationService.ACTION_REFRESH_BOOKSHELF))
            } catch (e: Exception) {
                updateNotification(e.message ?: getString(R.string.novel_translation_notification_failed))
            } finally {
                TranslationWorkState.setNovelRunning(this@NovelTranslationService, false)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        TranslationWorkState.setNovelRunning(this, false)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.action_novel_translation_title),
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String): Notification {
        val openIntent = Intent(this, LibraryActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_translate_24)
            .setContentTitle(getString(R.string.action_novel_translation_title))
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(content: String) {
        val notification = createNotification(content).apply {
            flags = flags and Notification.FLAG_ONGOING_EVENT.inv()
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
    }

    private suspend fun resolveNovelServerTimestampMillis(modelTier: String): Long {
        val uid = auth.currentUser?.uid ?: return System.currentTimeMillis()
        val novelRegex = Regex("Novel Translation \\((\\d+) chars, ([^)]+)\\)")
        val expectedModel = when (modelTier.uppercase(Locale.getDefault())) {
            "PRO" -> "pro"
            "ADVANCED" -> "advanced"
            else -> modelTier.lowercase(Locale.getDefault())
        }

        repeat(3) {
            try {
                val snapshot = db.collection("users")
                    .document(uid)
                    .collection("transactions")
                    .orderBy("timestamp", Query.Direction.DESCENDING)
                    .limit(40)
                    .get()
                    .await()

                val tierMatched = snapshot.documents.firstOrNull { doc ->
                    val desc = doc.getString("description").orEmpty()
                    val match = novelRegex.find(desc) ?: return@firstOrNull false
                    val model = match.groupValues.getOrNull(2).orEmpty().lowercase(Locale.getDefault())
                    model.contains(expectedModel)
                }?.getDate("timestamp")?.time

                if (tierMatched != null) return tierMatched

                val anyNovel = snapshot.documents.firstOrNull { doc ->
                    novelRegex.containsMatchIn(doc.getString("description").orEmpty())
                }?.getDate("timestamp")?.time

                if (anyNovel != null) return anyNovel
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read novel transaction timestamp", e)
            }
            delay(300)
        }

        return fetchServerNowMillis(uid)
    }

    private suspend fun fetchServerNowMillis(uid: String): Long {
        return try {
            val ref = db.collection("users")
                .document(uid)
                .collection("client_sync")
                .document("clock")

            ref.set(mapOf("serverTime" to FieldValue.serverTimestamp()), SetOptions.merge()).await()
            ref.get(Source.SERVER).await().getDate("serverTime")?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch server time, fallback local time", e)
            System.currentTimeMillis()
        }
    }

    private suspend fun saveNovelToBookshelf(
        originalText: String,
        translatedText: String,
        modelTier: String,
        titleTimestampMillis: Long
    ) {
        withContext(Dispatchers.IO) {
            val rootDir = File(filesDir, "Books")
            if (!rootDir.exists()) rootDir.mkdirs()

            val folder = createUniqueNovelFolder(rootDir)
            folder.mkdirs()

            val coverFile = File(folder, "cover_black.png")
            createBlackCover(coverFile, modelTier)

            File(folder, "original.txt").writeText(originalText)
            File(folder, "translated.txt").writeText(translatedText)

            val timestamp = SimpleDateFormat("MM - dd - mm - ss", Locale.getDefault()).format(Date(titleTimestampMillis))
            val title = getString(R.string.novel_title_with_timestamp, timestamp)
            val metadata = JSONObject().apply {
                put("id", folder.name)
                put("title", title)
                put("lastReadIndex", 0)
                put("isCompleted", false)
                put("lastModified", titleTimestampMillis)
                put("coverPath", coverFile.absolutePath)
                put("pageCount", 1)
                put("translationTier", modelTier)
                put("isCoverHidden", false)
                put("isBookmarked", false)
                put("bookType", "NOVEL")
            }

            File(folder, "metadata.json").writeText(metadata.toString())
        }
    }

    private fun createUniqueNovelFolder(rootDir: File): File {
        val base = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
        var folder = File(rootDir, "Novel_$base")
        var suffix = 1
        while (folder.exists()) {
            folder = File(rootDir, "Novel_${base}_$suffix")
            suffix++
        }
        return folder
    }

    private fun createBlackCover(outputFile: File, modelTier: String) {
        val bitmap = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)

        val lineColor = when (modelTier) {
            "PRO" -> ContextCompat.getColor(this, R.color.tier_pro)
            "ADVANCED" -> ContextCompat.getColor(this, R.color.tier_advanced)
            else -> null
        }
        if (lineColor != null) {
            val lineHeight = 16f
            val paint = Paint().apply {
                color = lineColor
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            canvas.drawRect(
                0f,
                bitmap.height - lineHeight,
                bitmap.width.toFloat(),
                bitmap.height.toFloat(),
                paint
            )
        }

        FileOutputStream(outputFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
    }
}
