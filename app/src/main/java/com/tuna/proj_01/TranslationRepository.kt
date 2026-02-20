package com.tuna.proj_01

import android.util.Log
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

object TranslationRepository {
    // "asia-northeast3" 리전이 맞는지 확인하세요
    private val functions = FirebaseFunctions.getInstance("asia-northeast3")
    private const val TAG = "MangaDebug"

    suspend fun translate(
        blocks: List<MangaBlock>,
        targetLang: String,
        imageCount: Int,
        serviceType: String = "MANGA",
        modelTier: String = "ADVANCED" // [추가] "ADVANCED" or "PRO"
    ): List<MangaBlock> {
        val requests = blocks.map { mapOf("id" to it.id, "text" to it.originalText) }

        val data = hashMapOf(
            "requests" to requests,
            "targetLang" to targetLang,
            "imageCount" to imageCount,
            "serviceType" to serviceType,
            "modelTier" to modelTier // [추가] 모델 등급 전송
        )

        Log.d(TAG, "🚀 [요청] AI 번역 시작 ($serviceType / $imageCount 장 / $modelTier)")

        try {
            val callable = functions.getHttpsCallable("translateManga")
            callable.setTimeout(300L, TimeUnit.SECONDS)

            val result = callable.call(data).await()
            val resultMap = result.data as? Map<String, Any>

            if (resultMap != null) {
                val cost = (resultMap["cost"] as? Number)?.toLong() ?: 0L
                val currency = resultMap["currency"] as? String ?: "Silver"
                val usage = resultMap["usage"] as? Map<String, Any>
                val totalTokens = usage?.get("total") ?: "unknown"
                val duration = usage?.get("durationMs") ?: 0

                Log.d(TAG, "✅ [성공] 서버 응답 완료 (소요: ${duration}ms, 토큰: $totalTokens)")
                Log.d(TAG, "   └─ 차감된 $currency: $cost")

                val resultsList = resultMap["results"] as? List<Map<String, Any>>

                resultsList?.forEach { item ->
                    val id = item["id"].toString().toIntOrNull()
                    val text = item["text"] as? String ?: ""

                    if (id != null) {
                        blocks.find { it.id == id }?.translatedText = text
                    }
                }

                return blocks
            } else {
                throw Exception("Empty server response.")
            }

        } catch (e: Exception) {
            // [Fix] 코루틴 취소 에러는 네트워크 에러로 처리하지 않고 그대로 던짐
            if (e is CancellationException) throw e

            val msg = if (e is FirebaseFunctionsException) {
                val code = e.code
                val details = e.message
                Log.e(TAG, "🔥 [서버 에러] Code: $code, Msg: $details")

                when (code) {
                    FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED -> "Insufficient Silver/Gold. Please recharge."
                    FirebaseFunctionsException.Code.UNAUTHENTICATED -> "Login required."
                    FirebaseFunctionsException.Code.DEADLINE_EXCEEDED -> "Timed out. Please try again."
                    else -> "Server error: ${e.message}"
                }
            } else {
                Log.e(TAG, "🔥 [네트워크 에러] ${e.message}", e)
                "Check network connection."
            }
            throw Exception(msg)
        }
    }
}
