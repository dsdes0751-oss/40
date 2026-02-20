package com.tuna.proj_01

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val repository = BookRepository(context)
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    private val TAG = "MangaDebug"

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState

    // Silver Balance
    private val _userBalance = MutableStateFlow<Long>(0)
    val userBalance: StateFlow<Long> = _userBalance

    // [추가] Gold Balance
    private val _userGoldBalance = MutableStateFlow<Long>(0)
    val userGoldBalance: StateFlow<Long> = _userGoldBalance

    val bookList: StateFlow<List<Book>> = repository.allBooks
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private var balanceListener: ListenerRegistration? = null

    // 브로드캐스트 리시버 (서비스 상태 수신용)
    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MassTranslationService.BROADCAST_PROGRESS -> {
                    val current = intent.getIntExtra("CURRENT", 0)
                    val total = intent.getIntExtra("TOTAL", 0)
                    val msg = intent.getStringExtra("MESSAGE") ?: ""
                    _uiState.value = UiState.Loading("$msg\n($current / $total)\n[Can be stopped from notification]")
                }
                MassTranslationService.BROADCAST_COMPLETE -> {
                    val msg = intent.getStringExtra("MESSAGE") ?: "Done"
                    _uiState.value = UiState.Success(msg)
                }
                MassTranslationService.BROADCAST_ERROR -> {
                    val msg = intent.getStringExtra("MESSAGE") ?: "Error"
                    _uiState.value = UiState.Error(msg)
                }
                MassTranslationService.ACTION_REFRESH_BOOKSHELF -> {
                    refreshBookshelf()
                }
            }
        }
    }

    init {
        startBalanceListener()
        refreshBookshelf()
        registerServiceReceiver()
    }

    private fun registerServiceReceiver() {
        val filter = IntentFilter().apply {
            addAction(MassTranslationService.BROADCAST_PROGRESS)
            addAction(MassTranslationService.BROADCAST_COMPLETE)
            addAction(MassTranslationService.BROADCAST_ERROR)
            addAction(MassTranslationService.ACTION_REFRESH_BOOKSHELF)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(serviceReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            ContextCompat.registerReceiver(
                context,
                serviceReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    private fun startBalanceListener() {
        val uid = auth.currentUser?.uid ?: return
        balanceListener?.remove()
        balanceListener = db.collection("users").document(uid)
            .addSnapshotListener { snapshot, e ->
                if (e != null) return@addSnapshotListener
                if (snapshot != null && snapshot.exists()) {
                    val balance = snapshot.getLong("current_balance") ?: 0
                    val gold = snapshot.getLong("gold_balance") ?: 0 // [추가]
                    _userBalance.value = balance
                    _userGoldBalance.value = gold
                } else {
                    _userBalance.value = 0
                    _userGoldBalance.value = 0
                }
            }
    }

    fun refreshBookshelf() {
        viewModelScope.launch { repository.syncFileSystem() }
    }

    fun deleteBook(book: Book) {
        viewModelScope.launch { repository.deleteBook(book) }
    }

    fun toggleBookmark(book: Book) {
        viewModelScope.launch { repository.toggleBookmark(book.id) }
    }

    fun checkUserBalance() {
        startBalanceListener()
    }

    // 서비스에 "중단" 요청 (UI 버튼용, 실제로는 알림바에서 중단하는 게 더 확실함)
    fun stopTranslation() {
        val intent = Intent(context, MassTranslationService::class.java)
        intent.action = MassTranslationService.ACTION_STOP
        context.startService(intent)
    }

    // [변경] modelTier, targetLang 파라미터 추가
    fun processImages(uris: List<Uri>, selectedLang: String, targetLang: String = "Korean", modelTier: String = "ADVANCED") {
        if (uris.isEmpty()) return

        if (TranslationWorkState.isAnyTranslationRunning(context)) {
            val runningTask = TranslationWorkState.runningTaskName(context) ?: "다른 번역"
            _uiState.value = UiState.Error("$runningTask 진행 중에는 새 번역을 시작할 수 없습니다.")
            return
        }

        val sortedUris = sortUrisByName(uris)
        val totalCount = sortedUris.size

        // 0. 클라이언트 사이드 잔액 사전 체크 (빠른 피드백용)
        if (modelTier == "PRO") {
            if (_userGoldBalance.value < totalCount) {
                _uiState.value = UiState.Error("골드가 부족합니다! (보유: ${_userGoldBalance.value}, 필요: $totalCount)")
                return
            }
        } else if (modelTier == "ADVANCED") {
            if (_userBalance.value < totalCount) {
                _uiState.value = UiState.Error("실버가 부족합니다! (보유: ${_userBalance.value}, 필요: $totalCount)")
                return
            }
        }
        // STANDARD (Free) -> 통과

        viewModelScope.launch {
            _uiState.value = UiState.Loading("잔액 확인 중...")

            val uid = auth.currentUser?.uid
            if (uid == null) {
                _uiState.value = UiState.Error("로그인이 필요합니다.")
                return@launch
            }

            // 1. 서버 사이드 잔액 정밀 체크
            var hasBalance = true
            try {
                if (modelTier != "STANDARD") {
                    val snapshot = db.collection("users").document(uid).get().await()
                    if (modelTier == "PRO") {
                        val gold = snapshot.getLong("gold_balance") ?: 0
                        hasBalance = gold >= totalCount
                    } else { // ADVANCED
                        val silver = snapshot.getLong("current_balance") ?: 0
                        hasBalance = silver >= totalCount
                    }
                }
            } catch (e: Exception) {
                hasBalance = false
            }

            if (!hasBalance) {
                val currency = if (modelTier == "PRO") "골드가" else "실버가"
                _uiState.value = UiState.Error("$currency 부족합니다! (충전 필요)")
                checkUserBalance()
                return@launch
            }

            _uiState.value = UiState.Loading("서비스 시작 중...")

            // 2. 책 폴더 생성
            val bookDir = createBookDirectory()
            repository.updateBookMetadata(bookDir.name)

            // 3. 데이터 홀더에 저장
            TranslationDataHolder.targetUris = sortedUris
            TranslationDataHolder.targetBookDir = bookDir

            // 4. 서비스 시작
            val serviceIntent = Intent(context, MassTranslationService::class.java).apply {
                action = MassTranslationService.ACTION_START
                putExtra("LANG", selectedLang)
                putExtra("TARGET_LANG", targetLang)
                putExtra("MODEL_TIER", modelTier) // [추가]
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }

    private fun createBookDirectory(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val bookName = "Book_$timestamp"
        val rootDir = File(context.filesDir, "Books")
        val bookDir = File(rootDir, bookName)
        if (!bookDir.exists()) bookDir.mkdirs()
        return bookDir
    }

    private fun sortUrisByName(uris: List<Uri>): List<Uri> {
        return uris.map { uri ->
            val name = getFileName(uri) ?: ""
            Pair(uri, name)
        }.sortedBy { it.second }.map { it.first }
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index != -1) result = cursor.getString(index)
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "파일명 로드 실패", e) }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != -1 && cut != null) result = result?.substring(cut + 1)
        }
        return result
    }

    override fun onCleared() {
        super.onCleared()
        balanceListener?.remove()
        try {
            context.unregisterReceiver(serviceReceiver)
        } catch (e: Exception) {}
    }
}
