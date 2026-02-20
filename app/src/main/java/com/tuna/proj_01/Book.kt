package com.tuna.proj_01

/**
 * Room DB 어노테이션(@Entity 등)을 모두 제거한 순수 데이터 클래스입니다.
 * 이제 메타데이터는 폴더 내의 JSON 파일로 관리됩니다.
 */
data class Book(
    val id: String,             // 폴더명 (Unique ID)
    val title: String,          // 책 제목
    val coverPath: String,      // 표지 이미지 경로
    val folderPath: String,     // 책 폴더 전체 경로
    val pageCount: Int = 0,     // 전체 페이지 수
    val lastReadIndex: Int = 0, // 마지막으로 읽은 위치 (0부터 시작)
    val isCompleted: Boolean = false, // Completed 여부
    val lastModified: Long = 0L, // 수정 시간 (이미지 갱신 감지용)
    val translationTier: String? = null, // 번역 등급: STANDARD, ADVANCED, PRO
    val isCoverHidden: Boolean = false, // 표지 숨김 여부
    val isBookmarked: Boolean = false // 북마크 여부
) {
    // 진행률 계산 (0~100%)
    val progress: Int
        get() = if (pageCount > 0) ((lastReadIndex + 1) * 100) / pageCount else 0

    // 상태 텍스트
    val statusText: String
        get() = when {
            isCompleted -> "Completed"
            lastReadIndex > 0 -> "${progress}%"
            else -> "New"
        }
}
