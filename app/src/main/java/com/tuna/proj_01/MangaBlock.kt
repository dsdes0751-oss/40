package com.tuna.proj_01

import android.graphics.Rect

/**
 * 만화/화면 번역에서 인식된 텍스트 블록 정보를 담는 데이터 클래스.
 * MainActivity(이미지 파일)와 ScreenTranslationService(화면 오버레이)에서 공통으로 사용합니다.
 */
data class MangaBlock(
    var id: Int,
    var pageIndex: Int, // 서비스 모드에서는 0으로 고정
    val originalText: String,
    var translatedText: String = "",
    val boundingBox: Rect,
    val lineBoxes: List<Rect>,
    val isVertical: Boolean,
    var bubbleRect: Rect? = null,
    var bubbleIsDark: Boolean = false,
    var bubbleAvgLuma: Float = 0f
)
