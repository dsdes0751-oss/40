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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MassTranslationService : Service() {

    companion object {
        const val ACTION_START = "com.tuna.proj_01.action.MASS_START"
        const val ACTION_STOP = "com.tuna.proj_01.action.MASS_STOP"
        const val CHANNEL_ID = "MassTranslationChannel"
        const val NOTIFICATION_ID = 999

        // UI ?낅뜲?댄듃瑜??꾪븳 釉뚮줈?쒖틦?ㅽ듃 ?≪뀡
        const val BROADCAST_PROGRESS = "com.tuna.proj_01.broadcast.PROGRESS"
        const val BROADCAST_COMPLETE = "com.tuna.proj_01.broadcast.COMPLETE"
        const val BROADCAST_ERROR = "com.tuna.proj_01.broadcast.ERROR"

        // ?쒖옱 媛깆떊???≪뀡
        const val ACTION_REFRESH_BOOKSHELF = "com.tuna.proj_01.ACTION_REFRESH_BOOKSHELF"
        const val EXTRA_FORCE_FINAL_SYNC = "FORCE_FINAL_SYNC"

        // [蹂寃? MAX_DETECTED_CHAR_COUNT ?곸닔 ?쒓굅 ??getMaxDetectedCharCount()濡??숈쟻 ?곸슜
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private val repository by lazy { BookRepository(applicationContext) }
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }

    // [Volatile] ?щ윭 ?ㅻ젅?쒖뿉??利됱떆 以묐떒 ?곹깭瑜??몄??섎룄濡??ㅼ젙
    @Volatile
    private var isCancelled = false

    private var wakeLock: android.os.PowerManager.WakeLock? = null

    // [蹂寃? ?寃??몄뼱???곕Ⅸ ?숈쟻 湲?????쒗븳???꾪븳 ?몄뒪?댁뒪 蹂??
    private var targetLang: String = "Korean"

    // [蹂寃? ?寃??몄뼱???곕Ⅸ ?숈쟻 湲?????쒗븳 (?곸뼱: 425, 湲곕낯: 250)
    private fun getMaxDetectedCharCount(): Int {
        return if (targetLang == "English") 425 else 250
    }

    private val MAX_IMAGE_DIMENSION = 2560

    // [?ㅼ젙] 5pages??臾띠뼱??AI ?붿껌 (蹂寃? 3 ??5, ?됯퇏 ?좏겙 ?뚮え ?⑥쑉 ?μ긽)
    private val PARALLEL_BATCH_SIZE = 5
    // [?ㅼ젙] ???댁뿉 ?숈떆???ㅽ뻾??諛곗튂 ??(5媛? -> 利????댁뿉 25pages(5x5) 泥섎━
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
                    val runningTask = TranslationWorkState.runningTaskName(this@MassTranslationService)
                        ?: getString(R.string.translation_running_other_task)
                    putExtra("MESSAGE", getString(R.string.translation_running_block_message, runningTask))
                })
                stopSelf()
                return START_NOT_STICKY
            }

            val lang = intent.getStringExtra("LANG") ?: "Japanese"
            val targetLang = intent.getStringExtra("TARGET_LANG") ?: "Korean"
            this.targetLang = targetLang // [蹂寃? ?숈쟻 湲?????쒗븳???ъ슜???몄뒪?댁뒪 蹂?섏뿉 ???
            val modelTier = intent.getStringExtra("MODEL_TIER") ?: "ADVANCED" // [異붽?]
            val uris = TranslationDataHolder.targetUris
            val bookDir = TranslationDataHolder.targetBookDir

            if (uris.isNotEmpty() && bookDir != null) {
                isCancelled = false
                TranslationWorkState.setMassRunning(this, true)
                acquireWakeLock()
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(uris.size, 0, getString(R.string.mass_status_preparing), true)
                )

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
        updateNotification(getString(R.string.mass_status_finalizing), 0, 0, false)
        // 利됱떆 醫낅즺?섏? ?딄퀬, 吏꾪뻾 以묒씤 諛곗튂媛 ?pages???쒓컙??以?(濡쒖쭅?먯꽌 泥섎━)
    }

    /**
     * [??湲곕컲 泥섎━ 濡쒖쭅]
     * STANDARD 紐⑤뱶(濡쒖뺄 踰덉뿭)? ADVANCED/PRO(?쒕쾭 踰덉뿭)瑜?遺꾧린 泥섎━
     */
    private fun startTurnBasedTranslationProcess(uris: List<Uri>, bookDir: File, lang: String, targetLang: String, modelTier: String) {
        serviceScope.launch {
            val totalCount = uris.size
            var globalSuccessCount = 0
            val bookId = bookDir.name
            val skippedLargeTextPages = mutableListOf<Int>()

            // ???댁쓽 ?ш린 = 25pages (蹂寃? 9 ??25)
            val turnSize = PARALLEL_BATCH_SIZE * MAX_CONCURRENT_REQUESTS

            try {
                // [STANDARD 紐⑤뱶 ?꾩슜] ?몄뼱???ㅼ슫濡쒕뱶 泥댄겕
                if (modelTier == "STANDARD") {
                    updateNotification(getString(R.string.mass_status_checking_offline_model), totalCount, 0, true)
                    prepareLocalModel(lang, targetLang)
                }

                // ?꾩껜 ?대?吏瑜?25pages???섎닎 (???앹꽦)
                val turns = uris.chunked(turnSize)

                for ((turnIndex, turnUris) in turns.withIndex()) {
                    // [泥댄겕 ?ъ씤??1] ???쒖옉 ??痍⑥냼 ?뺤씤
                    if (isCancelled) throw CancellationException()

                    val currentTurnStartCount = turnIndex * turnSize

                    // --- [Step 1] OCR ?섑뻾 ---
                    updateNotification(
                        getString(R.string.mass_status_analyzing_positions_format, currentTurnStartCount, totalCount),
                        totalCount,
                        currentTurnStartCount,
                        true
                    )

                    val ocrJobs = turnUris.mapIndexed { localIndex, uri ->
                        val globalIndex = currentTurnStartCount + localIndex
                        async(Dispatchers.Default) {
                            if (isCancelled) throw CancellationException()
                            val blocks = performSingleOCR(uri, lang, globalIndex, skippedLargeTextPages)
                            if (globalIndex % 3 == 0) {
                                sendProgressBroadcast(
                                    globalIndex,
                                    totalCount,
                                    getString(R.string.mass_progress_analyzing_format, globalIndex + 1, totalCount)
                                )
                            }
                            PageData(uri, blocks, globalIndex)
                        }
                    }
                    val pageDataList = ocrJobs.awaitAll()

                    // --- [Step 2] 踰덉뿭 (Local vs Server) ---
                    updateNotification(getString(R.string.mass_status_translating_rendering), totalCount, globalSuccessCount, true)

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
                                            serviceType = "MANGA_BATCH",
                                            modelTier = modelTier
                                        )
                                    }
                                }

                                // [蹂寃? 鍮?釉붾줉 ?섏씠吏 ???諛⑹? + 痍⑥냼 泥댄겕 蹂닿컯
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

                    // 諛곗튂???꾨즺 ?湲?
                    val results = translationJobs.awaitAll()
                    globalSuccessCount += results.sum()

                    val percent = if (totalCount > 0) (globalSuccessCount * 100) / totalCount else 0
                    val msg = getString(R.string.mass_progress_in_progress_format, globalSuccessCount, totalCount, percent)
                    BookMetadataManager.saveMetadata(bookDir, 0, totalCount, modelTier)
                    repository.updateBookMetadata(bookId)
                    sendBookshelfRefreshBroadcast()
                    updateNotification(msg, totalCount, globalSuccessCount, true)
                    sendProgressBroadcast(globalSuccessCount, totalCount, msg)
                }

                // --- ?꾩껜 ?꾨즺 ---
                if (!isCancelled) {
                    updateNotification(
                        getString(R.string.mass_status_complete_format, totalCount),
                        totalCount,
                        totalCount,
                        false
                    )
                    val skipMessage = if (skippedLargeTextPages.isNotEmpty()) {
                        val pageLabels = skippedLargeTextPages
                            .distinct()
                            .sorted()
                            .joinToString(",") { (it + 1).toString() }
                        getString(R.string.mass_skip_pages_suffix_format, pageLabels)
                    } else {
                        ""
                    }
                    sendBroadcast(Intent(BROADCAST_COMPLETE).apply {
                        putExtra(
                            "MESSAGE",
                            getString(R.string.mass_complete_message_format, globalSuccessCount) + skipMessage
                        )
                    })
                    syncMassBookTitleWithServerTime(bookDir, modelTier)
                    BookMetadataManager.saveMetadata(bookDir, 0, totalCount, modelTier)
                    repository.updateBookMetadata(bookId)
                    sendBookshelfRefreshBroadcast(forceFinalSync = true)
                }

            } catch (e: CancellationException) {
                Log.d("MassService", "?묒뾽 痍⑥냼??(泥섎━??pages: $globalSuccessCount)")
                updateNotification(getString(R.string.mass_status_stopped), totalCount, globalSuccessCount, false)
                recordMassSessionInterrupted(globalSuccessCount, totalCount, modelTier)
                sendBroadcast(Intent(BROADCAST_COMPLETE).apply {
                    putExtra("MESSAGE", getString(R.string.mass_complete_stopped_format, globalSuccessCount))
                })
                syncMassBookTitleWithServerTime(bookDir, modelTier)
                BookMetadataManager.saveMetadata(bookDir, 0, totalCount, modelTier)
                repository.updateBookMetadata(bookId)
                sendBookshelfRefreshBroadcast(forceFinalSync = true)

            } catch (e: Exception) {
                Log.e("MassService", "Error", e)
                updateNotification(
                    getString(R.string.mass_error_prefix_format, e.message ?: getString(R.string.common_unknown)),
                    0,
                    0,
                    false
                )
                sendBroadcast(Intent(BROADCAST_ERROR).apply {
                    putExtra("MESSAGE", e.message ?: getString(R.string.common_unknown))
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

    // [New] ML Kit 踰덉뿭 紐⑤뜽 以鍮?
    private suspend fun recordMassSessionInterrupted(donePages: Int, totalPages: Int, modelTier: String) {
        try {
            val uid = auth.currentUser?.uid ?: return
            val currency = when (modelTier.uppercase()) {
                "PRO" -> "Gold"
                "ADVANCED" -> "Silver"
                else -> "Free"
            }

            db.collection("users")
                .document(uid)
                .collection("transactions")
                .add(
                    hashMapOf(
                        "uid" to uid,
                        "type" to "MASS_SESSION_INTERRUPTED",
                        "amount" to 0,
                        "currency" to currency,
                        "description" to "Manga batch interrupted ($donePages/$totalPages pages) [$modelTier]",
                        "timestamp" to FieldValue.serverTimestamp()
                    )
                )
                .await()
        } catch (e: Exception) {
            Log.w("MassService", "Failed to record interrupted mass session", e)
        }
    }

    private suspend fun syncMassBookTitleWithServerTime(bookDir: File, modelTier: String) {
        val titleTimestamp = resolveMassServerTimestampMillis(modelTier)
        val formattedTime = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.getDefault()).format(Date(titleTimestamp))
        val title = getString(R.string.mass_book_title_format, formattedTime)

        try {
            val metaFile = File(bookDir, "metadata.json")
            val json = if (metaFile.exists()) JSONObject(metaFile.readText()) else JSONObject()
            json.put("id", json.optString("id", bookDir.name))
            json.put("title", title)
            json.put("lastModified", titleTimestamp)
            if (!json.has("coverPath")) {
                val cover = bookDir.listFiles { f ->
                    val n = f.name.lowercase(Locale.getDefault())
                    n.endsWith(".jpg") || n.endsWith(".png")
                }?.sortedBy { it.name }?.firstOrNull()
                if (cover != null) json.put("coverPath", cover.absolutePath)
            }
            metaFile.writeText(json.toString())
        } catch (e: Exception) {
            Log.w("MassService", "Failed to sync mass book title", e)
        }
    }

    private suspend fun resolveMassServerTimestampMillis(modelTier: String): Long {
        val uid = auth.currentUser?.uid ?: return System.currentTimeMillis()
        val massRegex = Regex("Manga(?: Batch)? Translation \\((\\d+) pages\\) \\[([A-Z]+)]")
        val interruptedRegex = Regex("Manga batch interrupted \\((\\d+)/(\\d+) pages\\) \\[([A-Z]+)]")
        val normalizedTier = modelTier.uppercase(Locale.getDefault())

        return try {
            val snapshot = db.collection("users")
                .document(uid)
                .collection("transactions")
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(80)
                .get()
                .await()

            val tierMatchedMass = snapshot.documents.firstOrNull { doc ->
                val desc = doc.getString("description").orEmpty()
                val match = massRegex.find(desc) ?: return@firstOrNull false
                val tier = match.groupValues.getOrNull(2).orEmpty().uppercase(Locale.getDefault())
                tier == normalizedTier
            }?.getDate("timestamp")?.time
            if (tierMatchedMass != null) return tierMatchedMass

            val tierMatchedInterrupted = snapshot.documents.firstOrNull { doc ->
                val desc = doc.getString("description").orEmpty()
                val match = interruptedRegex.find(desc) ?: return@firstOrNull false
                val tier = match.groupValues.getOrNull(3).orEmpty().uppercase(Locale.getDefault())
                tier == normalizedTier
            }?.getDate("timestamp")?.time
            if (tierMatchedInterrupted != null) return tierMatchedInterrupted

            val anyMass = snapshot.documents.firstOrNull { doc ->
                massRegex.containsMatchIn(doc.getString("description").orEmpty())
            }?.getDate("timestamp")?.time
            if (anyMass != null) return anyMass

            fetchServerNowMillis(uid)
        } catch (e: Exception) {
            Log.w("MassService", "Failed to resolve mass timestamp", e)
            fetchServerNowMillis(uid)
        }
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
            Log.w("MassService", "Failed to fetch server time", e)
            System.currentTimeMillis()
        }
    }

    private suspend fun prepareLocalModel(sourceLang: String, targetLang: String) {
        val srcCode = mapLangCode(sourceLang)
        val tgtCode = mapLangCode(targetLang)
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(srcCode)
            .setTargetLanguage(tgtCode)
            .build()
        val translator = Translation.getClient(options)

        val conditions = DownloadConditions.Builder().requireWifi().build()
        // 紐⑤뜽 ?ㅼ슫濡쒕뱶 ?湲?(?대? ?덉쑝硫?利됱떆 由ы꽩)
        translator.downloadModelIfNeeded(conditions).await()
        translator.close()
    }

    // [New] ML Kit 濡쒖뺄 踰덉뿭 ?ㅽ뻾
    private suspend fun performLocalTranslation(blocks: List<MangaBlock>, sourceLang: String, targetLang: String) {
        val srcCode = mapLangCode(sourceLang)
        val tgtCode = mapLangCode(targetLang)
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(srcCode)
            .setTargetLanguage(tgtCode)
            .build()
        val translator = Translation.getClient(options)

        try {
            // 諛곗튂 泥섎━ ???媛쒕퀎 泥섎━ (ML Kit??濡쒖뺄?대씪 鍮좊쫫)
            // Deferred濡?蹂묐젹 ?ㅽ뻾 媛?ν븯吏留?由ъ냼??愿由?李⑥썝?먯꽌 ?쒖감 ?ㅽ뻾 沅똯ages
            blocks.forEach { block ->
                try {
                    val result = translator.translate(block.originalText).await()
                    block.translatedText = result
                } catch (e: Exception) {
                    block.translatedText = getString(R.string.mass_translation_failed)
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

            // [蹂寃? ?숈쟻 湲?????쒗븳 ?곸슜 (?곸뼱: 350?? 湲곕낯: 250??
            val detectedCharCount = blocks.sumOf { it.originalText.length }
            val maxCharCount = getMaxDetectedCharCount()
            if (detectedCharCount >= maxCharCount) {
                synchronized(skippedLargeTextPages) {
                    skippedLargeTextPages.add(index)
                }
                Log.w("MassService", "?섏씠吏 ${index + 1} 湲????珥덇낵濡?踰덉뿭 嫄대꼫? (媛먯?: $detectedCharCount, ?쒗븳: $maxCharCount)")
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
        if (isCancelled) return  // [異붽?] 利됱떆 諛섑솚
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

