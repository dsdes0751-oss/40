package com.tuna.proj_01

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class MassTranslationService : Service() {

    companion object {
        const val ACTION_START = "com.tuna.proj_01.action.MASS_START"
        const val ACTION_STOP = "com.tuna.proj_01.action.MASS_STOP"
        const val CHANNEL_ID = "MassTranslationChannel"
        const val NOTIFICATION_ID = 999

        // UI 업데이트를 위한 브로드캐스트 액션
        const val BROADCAST_PROGRESS = "com.tuna.proj_01.broadcast.PROGRESS"
        const val BROADCAST_COMPLETE = "com.tuna.proj_01.broadcast.COMPLETE"
        const val BROADCAST_ERROR = "com.tuna.proj_01.broadcast.ERROR"

        // 서재 갱신용 액션
        const val ACTION_REFRESH_BOOKSHELF = "com.tuna.proj_01.ACTION_REFRESH_BOOKSHELF"
        const val EXTRA_FORCE_FINAL_SYNC = "FORCE_FINAL_SYNC"

        // [변경] MAX_DETECTED_CHAR_COUNT 상수 제거 → getMaxDetectedCharCount()로 동적 적용
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private val repository by lazy { BookRepository(applicationContext) }

    // [Volatile] 여러 스레드에서 즉시 중단 상태를 인지하도록 설정
    @Volatile
    private var isCancelled = false

    private var wakeLock: android.os.PowerManager.WakeLock? = null

    // [변경] 타겟 언어에 따른 동적 글자 수 제한을 위한 인스턴스 변수
    private var targetLang: String = "Korean"

    // [변경] 타겟 언어에 따른 동적 글자 수 제한 (영어: 425, 기본: 250)
    private fun getMaxDetectedCharCount(): Int {
        return if (targetLang == "English") 425 else 250
    }

    private val MAX_IMAGE_DIMENSION = 2560

    // [설정] 5pages씩 묶어서 AI 요청 (변경: 3 → 5, 평균 토큰 소모 효율 향상)
    private val PARALLEL_BATCH_SIZE = 5
    // [설정] 한 턴에 동시에 실행할 배치 수 (5개) -> 즉 한 턴에 25pages(5x5) 처리
    private val MAX_CONCURRENT_REQUESTS = 5

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP) {
            stopTranslation()
            return START_NOT_STICKY
        }

        if (action == ACTION_START) {
            if (TranslationWorkState.isNovelRunning(this) || TranslationWorkState.isMassRunning(this)) {
                sendBroadcast(Intent(BROADCAST_ERROR).apply {
                    putExtra("MESSAGE", getString(R.string.translation_running_block_message, TranslationWorkState.runningTaskName(this@MassTranslationService) ?: "다른 번역"))
                })
                stopSelf()
                return START_NOT_STICKY
            }

            val lang = intent.getStringExtra("LANG") ?: "Japanese"
            val targetLang = intent.getStringExtra("TARGET_LANG") ?: "Korean"
            this.targetLang = targetLang // [변경] 동적 글자 수 제한에 사용할 인스턴스 변수에 저장
            val modelTier = intent.getStringExtra("MODEL_TIER") ?: "ADVANCED" // [추가]
            val uris = TranslationDataHolder.targetUris
            val bookDir = TranslationDataHolder.targetBookDir

            if (uris.isNotEmpty() && bookDir != null) {
                isCancelled = false
                TranslationWorkState.setMassRunning(this, true)
                acquireWakeLock()
                startForeground(NOTIFICATION_ID, createNotification(uris.size, 0, "Preparing...", true))

                startTurnBasedTranslationProcess(uris, bookDir, lang, targetLang, modelTier)
            } else {
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Proj01:MassTranslationWakelock")
            wakeLock?.acquire(20 * 60 * 1000L)
        } catch (e: Exception) {
            Log.e("MassService", "WakeLock acquire failed", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            Log.e("MassService", "WakeLock release failed", e)
        }
    }

    private fun stopTranslation() {
        isCancelled = true
        updateNotification("Finalizing...", 0, 0, false)
        // 즉시 종료하지 않고, 진행 중인 배치가 저pages될 시간을 줌 (로직에서 처리)
    }

    /**
     * [턴 기반 처리 로직]
     * STANDARD 모드(로컬 번역)와 ADVANCED/PRO(서버 번역)를 분기 처리
     */
    private fun startTurnBasedTranslationProcess(uris: List<Uri>, bookDir: File, lang: String, targetLang: String, modelTier: String) {
        serviceScope.launch {
            val totalCount = uris.size
            var globalSuccessCount = 0
            val bookId = bookDir.name
            val skippedLargeTextPages = mutableListOf<Int>()

            // 한 턴의 크기 = 25pages (변경: 9 → 25)
            val turnSize = PARALLEL_BATCH_SIZE * MAX_CONCURRENT_REQUESTS

            try {
                // [STANDARD 모드 전용] 언어팩 다운로드 체크
                if (modelTier == "STANDARD") {
                    updateNotification("Checking offline translation model...", totalCount, 0, true)
                    prepareLocalModel(lang, targetLang)
                }

                // 전체 이미지를 25pages씩 나눔 (턴 생성)
                val turns = uris.chunked(turnSize)

                for ((turnIndex, turnUris) in turns.withIndex()) {
                    // [체크 포인트 1] 턴 시작 전 취소 확인
                    if (isCancelled) throw CancellationException()

                    val currentTurnStartCount = turnIndex * turnSize

                    // --- [Step 1] OCR 수행 ---
                    updateNotification("Analyzing text positions... (${currentTurnStartCount}/$totalCount)", totalCount, currentTurnStartCount, true)

                    val ocrJobs = turnUris.mapIndexed { localIndex, uri ->
                        val globalIndex = currentTurnStartCount + localIndex
                        async(Dispatchers.Default) {
                            if (isCancelled) throw CancellationException()
                            val blocks = performSingleOCR(uri, lang, globalIndex, skippedLargeTextPages)
                            if (globalIndex % 3 == 0) {
                                sendProgressBroadcast(globalIndex, totalCount, "Analyzing text (${globalIndex + 1}/$totalCount)")
                            }
                            PageData(uri, blocks, globalIndex)
                        }
                    }
                    val pageDataList = ocrJobs.awaitAll()

                    // --- [Step 2] 번역 (Local vs Server) ---
                    updateNotification("Translating and rendering images...", totalCount, globalSuccessCount, true)

                    val translationBatches = pageDataList.chunked(PARALLEL_BATCH_SIZE)

                    val translationJobs = translationBatches.map { batch ->
                        async(Dispatchers.IO) {
                            try {
                                if (isCancelled) throw CancellationException()

                                val allBlocks = batch.flatMap { it.blocks }
                                if (allBlocks.isNotEmpty()) {
                                    if (modelTier == "STANDARD") {
                                        performLocalTranslation(allBlocks, lang, targetLang)
                                    } else {
                                        TranslationRepository.translate(
                                            blocks = allBlocks,
                                            targetLang = targetLang,
                                            imageCount = batch.size,
                                            serviceType = "MANGA",
                                            modelTier = modelTier
                                        )
                                    }
                                }

                                // [변경] 빈 블록 페이지 저장 방지 + 취소 체크 보강
                                var batchSuccessCount = 0
                                batch.forEach { page ->
                                    if (isCancelled) throw CancellationException()
                                    if (page.blocks.isNotEmpty()) {
                                        saveTranslatedImage(page, bookDir, lang)
                                        batchSuccessCount++
                                    }
                                }
                                batchSuccessCount
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { Log.e("MassService", "Batch failed", e); 0 }
                        }
                    }

                    // 배치들 완료 대기
                    val results = translationJobs.awaitAll()
                    globalSuccessCount += results.sum()

                    val percent = if (totalCount > 0) (globalSuccessCount * 100) / totalCount else 0
                    val msg = "Translation in progress ($globalSuccessCount/$totalCount - $percent%)"
                    BookMetadataManager.saveMetadata(bookDir, 0, totalCount, modelTier)
                    repository.updateBookMetadata(bookId)
                    sendBookshelfRefreshBroadcast()
                    updateNotification(msg, totalCount, globalSuccessCount, true)
                    sendProgressBroadcast(globalSuccessCount, totalCount, msg)
                }

                // --- 전체 완료 ---
                if (!isCancelled) {
                    updateNotification("Translation complete! ($totalCount pages)", totalCount, totalCount, false)
                    val skipMessage = if (skippedLargeTextPages.isNotEmpty()) {
                        val pageLabels = skippedLargeTextPages
                            .distinct()
                            .sorted()
                            .joinToString(",") { (it + 1).toString() }
                        " 과도한 글자 감지로 번역 건너뜀: ${pageLabels}페이지"
                    } else {
                        ""
                    }
                    sendBroadcast(Intent(BROADCAST_COMPLETE).apply {
                        putExtra("MESSAGE", "Completed! ($globalSuccessCount pages).$skipMessage")
                    })
                    BookMetadataManager.saveMetadata(bookDir, 0, totalCount, modelTier)
                    repository.updateBookMetadata(bookId)
                    sendBookshelfRefreshBroadcast(forceFinalSync = true)
                }

            } catch (e: CancellationException) {
                Log.d("MassService", "작업 취소됨 (처리된 pages: $globalSuccessCount)")
                updateNotification("Translation stopped.", totalCount, globalSuccessCount, false)
                sendBroadcast(Intent(BROADCAST_COMPLETE).apply {
                    putExtra("MESSAGE", "Task stopped. (Done: $globalSuccessCount pages)")
                })
                BookMetadataManager.saveMetadata(bookDir, 0, totalCount, modelTier)
                repository.updateBookMetadata(bookId)
                sendBookshelfRefreshBroadcast(forceFinalSync = true)

            } catch (e: Exception) {
                Log.e("MassService", "Error", e)
                updateNotification("Error: ${e.message}", 0, 0, false)
                sendBroadcast(Intent(BROADCAST_ERROR).apply {
                    putExtra("MESSAGE", e.message ?: "Unknown error")
                })
            } finally {
                TranslationDataHolder.clear()
                TranslationWorkState.setMassRunning(this@MassTranslationService, false)
                releaseWakeLock()
                if (!isCancelled) delay(3000)
                stopSelf()
            }
        }
    }

    // [New] ML Kit 번역 모델 준비
    private suspend fun prepareLocalModel(sourceLang: String, targetLang: String) {
        val srcCode = mapLangCode(sourceLang)
        val tgtCode = mapLangCode(targetLang)
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(srcCode)
            .setTargetLanguage(tgtCode)
            .build()
        val translator = Translation.getClient(options)

        val conditions = DownloadConditions.Builder().requireWifi().build()
        // 모델 다운로드 대기 (이미 있으면 즉시 리턴)
        translator.downloadModelIfNeeded(conditions).await()
        translator.close()
    }

    // [New] ML Kit 로컬 번역 실행
    private suspend fun performLocalTranslation(blocks: List<MangaBlock>, sourceLang: String, targetLang: String) {
        val srcCode = mapLangCode(sourceLang)
        val tgtCode = mapLangCode(targetLang)
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(srcCode)
            .setTargetLanguage(tgtCode)
            .build()
        val translator = Translation.getClient(options)

        try {
            // 배치 처리 대신 개별 처리 (ML Kit는 로컬이라 빠름)
            // Deferred로 병렬 실행 가능하지만 리소스 관리 차원에서 순차 실행 권pages
            blocks.forEach { block ->
                try {
                    val result = translator.translate(block.originalText).await()
                    block.translatedText = result
                } catch (e: Exception) {
                    block.translatedText = "Translation failed"
                }
            }
        } finally {
            translator.close()
        }
    }

    private fun mapLangCode(lang: String): String {
        return when(lang) {
            "Japanese" -> TranslateLanguage.JAPANESE
            "English" -> TranslateLanguage.ENGLISH
            "Chinese" -> TranslateLanguage.CHINESE
            "Korean" -> TranslateLanguage.KOREAN
            else -> TranslateLanguage.JAPANESE
        }
    }

    // --- Helper Functions ---

    private suspend fun performSingleOCR(uri: Uri, lang: String, index: Int, skippedLargeTextPages: MutableList<Int>): List<MangaBlock> {
        return try {
            val bitmap = loadBitmapDownsampled(uri) ?: return emptyList()
            val recognizer = OcrManager.getRecognizer(lang)
            val scaleFactor = 2.0f
            val shouldScale = bitmap.width * bitmap.height < 4000 * 4000
            val ocrBitmap = if (shouldScale) {
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * scaleFactor).toInt(), (bitmap.height * scaleFactor).toInt(), true)
            } else {
                bitmap
            }

            val preNormal = OcrPreprocessor.preprocessForOcr(ocrBitmap, OcrPreprocessor.Mode.NORMAL)
            val firstVisionText = recognizer.process(InputImage.fromBitmap(preNormal, 0)).await()
            var selectedVisionText = firstVisionText
            val firstScore = OcrPreprocessor.scoreTextQuality(firstVisionText.text)
            if (firstScore < 0.62f) {
                val preStrong = OcrPreprocessor.preprocessForOcr(ocrBitmap, OcrPreprocessor.Mode.STRONG)
                try {
                    val secondVisionText = recognizer.process(InputImage.fromBitmap(preStrong, 0)).await()
                    val secondScore = OcrPreprocessor.scoreTextQuality(secondVisionText.text)
                    Log.d("MassService", "OCR retry page=${index + 1} first=$firstScore second=$secondScore")
                    if (secondScore > firstScore) {
                        selectedVisionText = secondVisionText
                    }
                } finally {
                    if (!preStrong.isRecycled) preStrong.recycle()
                }
            }
            if (!preNormal.isRecycled) preNormal.recycle()

            val blocksRaw = MangaProcessor.processOCRResult(
                selectedVisionText,
                lang,
                MangaProcessor.MergePolicy.CONSERVATIVE
            )
            val blocks = if (shouldScale) {
                blocksRaw.map { block ->
                    block.copy(
                        boundingBox = scaleRect(block.boundingBox, 1 / scaleFactor),
                        lineBoxes = block.lineBoxes.map { scaleRect(it, 1 / scaleFactor) },
                        bubbleRect = null,
                        bubbleIsDark = false,
                        bubbleAvgLuma = 0f
                    )
                }
            } else {
                blocksRaw
            }

            // [변경] 동적 글자 수 제한 적용 (영어: 350자, 기본: 250자)
            val detectedCharCount = blocks.sumOf { it.originalText.length }
            val maxCharCount = getMaxDetectedCharCount()
            if (detectedCharCount >= maxCharCount) {
                synchronized(skippedLargeTextPages) {
                    skippedLargeTextPages.add(index)
                }
                Log.w("MassService", "페이지 ${index + 1} 글자 수 초과로 번역 건너뜀 (감지: $detectedCharCount, 제한: $maxCharCount)")
                if (ocrBitmap != bitmap && !ocrBitmap.isRecycled) ocrBitmap.recycle()
                bitmap.recycle()
                recognizer.close()
                return emptyList()
            }

            blocks.forEach {
                it.id = index * 1000 + it.id
                it.pageIndex = index
            }
            if (ocrBitmap != bitmap && !ocrBitmap.isRecycled) ocrBitmap.recycle()
            bitmap.recycle()
            recognizer.close()
            blocks
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun scaleRect(rect: Rect, factor: Float): Rect {
        return Rect(
            (rect.left * factor).toInt(),
            (rect.top * factor).toInt(),
            (rect.right * factor).toInt(),
            (rect.bottom * factor).toInt()
        )
    }

    private suspend fun saveTranslatedImage(page: PageData, bookDir: File, sourceLang: String) {
        if (isCancelled) return  // [추가] 즉시 반환
        try {
            val fileName = String.format(Locale.US, "%03d.jpg", page.pageIndex + 1)
            val file = File(bookDir, fileName)

            val originBitmap = loadBitmapDownsampled(page.uri) ?: return
            val resultBitmap = originBitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(resultBitmap)

            MangaProcessor.drawAllBlocks(canvas, originBitmap, page.blocks, sourceLang)

            if (file.exists()) file.delete()
            FileOutputStream(file, false).use { out ->
                resultBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            originBitmap.recycle()
            resultBitmap.recycle()
        } catch (e: Exception) {
            Log.e("MassService", "Save Failed index ${page.pageIndex}", e)
        }
    }

    private fun loadBitmapDownsampled(uri: Uri): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            options.inSampleSize = calculateInSampleSize(options, MAX_IMAGE_DIMENSION, MAX_IMAGE_DIMENSION)
            options.inJustDecodeBounds = false
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        } catch (e: Exception) { null }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) inSampleSize *= 2
        }
        return inSampleSize
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Bulk translation", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(max: Int, progress: Int, content: String, ongoing: Boolean): Notification {
        val stopIntent = Intent(this, MassTranslationService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Translating manga")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(ongoing)
            .setProgress(max, progress, max == 0)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop task", stopPendingIntent)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(content: String, max: Int, progress: Int, ongoing: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(max, progress, content, ongoing))
    }

    private fun sendProgressBroadcast(current: Int, total: Int, msg: String) {
        val intent = Intent(BROADCAST_PROGRESS).apply {
            putExtra("CURRENT", current)
            putExtra("TOTAL", total)
            putExtra("MESSAGE", msg)
        }
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun sendBookshelfRefreshBroadcast(forceFinalSync: Boolean = false) {
        val intent = Intent(ACTION_REFRESH_BOOKSHELF).apply {
            putExtra(EXTRA_FORCE_FINAL_SYNC, forceFinalSync)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        TranslationWorkState.setMassRunning(this, false)
        super.onDestroy()
        serviceScope.cancel()
        releaseWakeLock()
    }

    data class PageData(val uri: Uri, val blocks: List<MangaBlock>, val pageIndex: Int)
}
