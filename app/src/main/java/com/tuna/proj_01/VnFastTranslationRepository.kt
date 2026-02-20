package com.tuna.proj_01

import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await

object VnFastTranslationRepository {
    private val functions = FirebaseFunctions.getInstance("asia-northeast3")

    data class VnFastRequest(
        val id: Any,
        val text: String
    )

    data class VnFastResult(
        val id: String,
        val text: String
    )

    data class VnFastResponse(
        val results: List<VnFastResult>,
        val usedOutputChars: Long,
        val remainingVnChars: Long
    )

    suspend fun translateVnFast(
        requests: List<VnFastRequest>,
        targetLang: String,
        sourceLang: String? = null
    ): VnFastResponse {
        val requestPayload = requests.map { mapOf("id" to it.id, "text" to it.text) }

        val data = hashMapOf<String, Any>(
            "requests" to requestPayload,
            "targetLang" to targetLang
        )
        if (!sourceLang.isNullOrBlank()) {
            data["sourceLang"] = sourceLang
        }

        val result = functions.getHttpsCallable("translateVnFast").call(data).await()
        val map = result.data as? Map<*, *> ?: error("Empty response")
        val resultsList = map["results"] as? List<*> ?: emptyList<Any>()

        val parsedResults = resultsList.mapNotNull { item ->
            val itemMap = item as? Map<*, *> ?: return@mapNotNull null
            val id = itemMap["id"]?.toString() ?: return@mapNotNull null
            val text = itemMap["text"]?.toString() ?: ""
            VnFastResult(id = id, text = text)
        }

        return VnFastResponse(
            results = parsedResults,
            usedOutputChars = (map["usedOutputChars"] as? Number)?.toLong() ?: 0L,
            remainingVnChars = (map["remainingVnChars"] as? Number)?.toLong() ?: 0L
        )
    }
}
