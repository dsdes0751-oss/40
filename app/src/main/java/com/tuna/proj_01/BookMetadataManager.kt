package com.tuna.proj_01

import org.json.JSONObject
import java.io.File
import java.io.FileReader
import java.io.FileWriter

/**
 * 책별 메타데이터(metadata.json)를 관리하는 싱글톤 객체
 * JSON 형식을 사용하여 읽은 위치와 시간을 영구 저장합니다.
 */
object BookMetadataManager {
    private const val FILE_NAME = "metadata.json"

    // 메타데이터 저장
    // [변경] isCoverHidden 파라미터 추가
    fun saveMetadata(bookFolder: File, lastReadIndex: Int, pageCount: Int, translationTier: String? = null, isCoverHidden: Boolean? = null, isBookmarked: Boolean? = null) {
        try {
            val file = File(bookFolder, FILE_NAME)
            val json = JSONObject()

            // 기존 데이터가 있다면 읽어서 병합 (태그 기능 확장 대비)
            if (file.exists()) {
                val existing = loadJSON(file)
                if (existing != null) {
                    val keys = existing.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        json.put(key, existing.get(key))
                    }
                }
            }

            json.put("lastReadIndex", lastReadIndex)
            json.put("lastReadTime", System.currentTimeMillis())

            // 마지막 페이지 도달 시 완독 처리
            val isCompleted = (lastReadIndex >= pageCount - 1)
            json.put("isCompleted", isCompleted)

            // 번역 등급 저장 (기존 값을 유지하거나 덮어쓰기)
            if (translationTier != null) {
                json.put("translationTier", translationTier)
            }

            // 표지 숨김 여부 저장
            if (isCoverHidden != null) {
                json.put("isCoverHidden", isCoverHidden)
            }

            // 북마크 여부 저장
            if (isBookmarked != null) {
                json.put("isBookmarked", isBookmarked)
            }

            val writer = FileWriter(file)
            writer.write(json.toString())
            writer.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    data class Metadata(
        val lastReadIndex: Int,
        val lastReadTime: Long,
        val isCompleted: Boolean,
        val translationTier: String?,
        val isCoverHidden: Boolean,
        val isBookmarked: Boolean
    )

    // 메타데이터 불러오기 (없으면 기본값 반환)
    fun loadMetadata(bookFolder: File): Metadata {
        val file = File(bookFolder, FILE_NAME)
        if (!file.exists()) return Metadata(0, 0L, false, null,
            isCoverHidden = false,
            isBookmarked = false
        )

        return try {
            val json = loadJSON(file) ?: return Metadata(0, 0L, false, null,
                isCoverHidden = false,
                isBookmarked = false
            )
            val idx = json.optInt("lastReadIndex", 0)
            val time = json.optLong("lastReadTime", 0L)
            val completed = json.optBoolean("isCompleted", false)
            val tier = if (json.has("translationTier")) json.getString("translationTier") else null
            val hidden = json.optBoolean("isCoverHidden", false)
            val bookmarked = json.optBoolean("isBookmarked", false)

            Metadata(idx, time, completed, tier, hidden, bookmarked)
        } catch (_: Exception) {
            Metadata(0, 0L, false, null, isCoverHidden = false, isBookmarked = false)
        }
    }

    private fun loadJSON(file: File): JSONObject? {
        return try {
            val reader = FileReader(file)
            val content = reader.readText()
            reader.close()
            JSONObject(content)
        } catch (_: Exception) {
            null
        }
    }
}
