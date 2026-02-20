package com.tuna.proj_01

import android.net.Uri

/**
 * 대용량 이미지 리스트를 액티비티 간에 전달하기 위한 데이터 홀더 (Singleton)
 * Intent TransactionTooLargeException 방지용
 */
object ImageDataHolder {
    private var imageUris: List<Uri>? = null

    fun setUris(uris: List<Uri>) {
        imageUris = uris
    }

    fun getUris(): List<Uri> {
        return imageUris ?: emptyList()
    }

    fun clear() {
        imageUris = null
    }
}