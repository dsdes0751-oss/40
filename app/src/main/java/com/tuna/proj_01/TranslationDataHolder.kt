package com.tuna.proj_01

import android.net.Uri
import java.io.File

/**
 * 인텐트로 대량의 URI 리스트를 넘기면 TransactionTooLargeException이 발생할 수 있습니다.
 * 이를 방지하기 위해 데이터를 메모리에 임시 보관하는 싱글톤 객체입니다.
 */
object TranslationDataHolder {
    var targetUris: List<Uri> = emptyList()
    var targetBookDir: File? = null

    fun clear() {
        targetUris = emptyList()
        targetBookDir = null
    }
}