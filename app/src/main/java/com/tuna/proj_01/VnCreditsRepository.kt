package com.tuna.proj_01

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await

object VnCreditsRepository {
    private val functions = FirebaseFunctions.getInstance("asia-northeast3")
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    data class PurchaseResult(
        val newSilverBalance: Long,
        val newVnCharBalance: Long
    )

    suspend fun purchaseVnCredits(packageId: String): PurchaseResult {
        val payload = hashMapOf("packageId" to packageId)
        val result = functions.getHttpsCallable("purchaseVnCredits").call(payload).await()
        val map = result.data as? Map<*, *> ?: error("Empty response")

        val newSilverBalance = (map["newSilverBalance"] as? Number)?.toLong() ?: 0L
        val newVnCharBalance = (map["newVnCharBalance"] as? Number)?.toLong() ?: 0L
        return PurchaseResult(newSilverBalance, newVnCharBalance)
    }

    suspend fun getVnCharBalance(): Long {
        val uid = auth.currentUser?.uid ?: return 0L
        val snapshot = db.collection("users").document(uid).get().await()
        return snapshot.getLong("vn_char_balance") ?: 0L
    }
}
