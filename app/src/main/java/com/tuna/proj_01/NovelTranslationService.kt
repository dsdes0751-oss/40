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
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NovelTranslationService : Service() {

    companion object {
        const val ACTION_START = "com.tuna.proj_01.action.NOVEL_TRANSLATION_START"
        const val CHANNEL_ID = "NovelTranslationChannel"
        const val NOTIFICATION_ID = 1201

        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_TARGET_LANG = "extra_target_lang"
        const val EXTRA_MODEL_TIER = "extra_model_tier"
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

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

                saveNovelToBookshelf(text, translatedText)

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

    private suspend fun saveNovelToBookshelf(originalText: String, translatedText: String) {
        withContext(Dispatchers.IO) {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val rootDir = File(filesDir, "Books")
            if (!rootDir.exists()) rootDir.mkdirs()

            val folder = File(rootDir, "Novel_$timestamp")
            folder.mkdirs()

            val coverFile = File(folder, "cover_black.png")
            createBlackCover(coverFile)

            File(folder, "original.txt").writeText(originalText)
            File(folder, "translated.txt").writeText(translatedText)

            val metadata = """
                {
                  "id": "${folder.name}",
                  "title": "소설번역 ${timestamp}",
                  "lastReadIndex": 0,
                  "isCompleted": false,
                  "lastModified": ${System.currentTimeMillis()},
                  "coverPath": "${coverFile.absolutePath}",
                  "pageCount": 1,
                  "translationTier": "NOVEL",
                  "isCoverHidden": false,
                  "isBookmarked": false,
                  "bookType": "NOVEL"
                }
            """.trimIndent()

            File(folder, "metadata.json").writeText(metadata)
        }
    }

    private fun createBlackCover(outputFile: File) {
        val bitmap = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        FileOutputStream(outputFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
    }
}
