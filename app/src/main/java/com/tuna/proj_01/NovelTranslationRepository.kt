package com.tuna.proj_01

import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await

object NovelTranslationRepository {
    private val functions = FirebaseFunctions.getInstance("asia-northeast3")

    suspend fun translateTxt(
        text: String,
        targetLang: String,
        modelTier: String
    ): Pair<String, Long> {
        val data = hashMapOf(
            "text" to text,
            "targetLang" to targetLang,
            "modelTier" to modelTier
        )

        val result = functions
            .getHttpsCallable("translateNovelTxt")
            .call(data)
            .await()

        val map = result.data as? Map<*, *> ?: error("Empty response")
        val translated = map["translatedText"] as? String ?: ""
        val cost = (map["cost"] as? Number)?.toLong() ?: 0L
        return translated to cost
    }
}
