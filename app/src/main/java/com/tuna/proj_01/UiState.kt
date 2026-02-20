package com.tuna.proj_01

/**
 * UI 상태를 관리하기 위한 Sealed Class
 * Loading: 로딩 중 (메시지 포함)
 * Success: 작업 성공 (데이터 포함)
 * Error: 실패 (에러 메시지 및 재시도 액션 포함)
 * Idle: 대기 상태
 */
sealed class UiState {
    object Idle : UiState()
    data class Loading(val message: String) : UiState()
    data class Success<T>(val data: T? = null) : UiState()
    data class Error(val message: String, val onRetry: (() -> Unit)? = null) : UiState()
}