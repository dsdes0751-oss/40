package com.tuna.proj_01

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Room DB(AppDatabase)를 제거하고, 파일 시스템과 JSON을 이용하여
 * 책 데이터를 관리하는 리포지토리입니다.
 * * [최적화] 불필요한 전체 스캔을 줄이고, JSON 캐시를 적극 활용하도록 개선되었습니다.
 * * [개선] 책 제목을 '월/일 시:분' 포맷으로 자동 변환합니다.
 */
class BookRepository(context: Context) {

    // 앱 내부 저장소의 'Books' 폴더
    private val rootDir: File = File(context.filesDir, "Books")

    // UI에서 구독할 책 목록 상태
    private val _allBooks = MutableStateFlow<List<Book>>(emptyList())
    val allBooks: StateFlow<List<Book>> = _allBooks.asStateFlow()

    init {
        if (!rootDir.exists()) rootDir.mkdirs()
    }

    suspend fun syncFileSystem() = withContext(Dispatchers.IO) {
        if (!rootDir.exists()) {
            _allBooks.value = emptyList()
            return@withContext
        }

        val bookFolders = rootDir.listFiles()?.filter { it.isDirectory } ?: emptyList()

        val list = bookFolders.mapNotNull { folder ->
            loadOrInitBookMetadata(folder, forceScan = false)
        }.sortedWith(
            compareByDescending<Book> { it.isBookmarked }
                .thenByDescending { it.lastModified }
        )

        _allBooks.value = list
    }

    suspend fun updateBookMetadata(bookId: String) = withContext(Dispatchers.IO) {
        val folder = File(rootDir, bookId)
        if (!folder.exists()) return@withContext

        val updatedBook = loadOrInitBookMetadata(folder, forceScan = true) ?: return@withContext

        val currentList = _allBooks.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == bookId }

        if (index != -1) {
            currentList[index] = updatedBook
        } else {
            currentList.add(0, updatedBook)
        }
        _allBooks.value = currentList
    }

    suspend fun saveProgress(bookId: String, pageIndex: Int, totalPages: Int) = withContext(Dispatchers.IO) {
        val folder = File(rootDir, bookId)
        if (!folder.exists()) return@withContext

        val currentBook = loadOrInitBookMetadata(folder, forceScan = false) ?: return@withContext

        val isCompleted = (pageIndex >= totalPages - 1)
        val updatedBook = currentBook.copy(
            lastReadIndex = pageIndex,
            pageCount = totalPages,
            isCompleted = isCompleted || currentBook.isCompleted,
            lastModified = System.currentTimeMillis()
        )

        saveBookToJson(folder, updatedBook)

        val currentList = _allBooks.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == bookId }
        if (index != -1) {
            currentList[index] = updatedBook
            _allBooks.value = currentList
        }
    }

    // [추가] 표지 숨김 상태 업데이트
    suspend fun updateCoverVisibility(bookId: String, isHidden: Boolean) = withContext(Dispatchers.IO) {
        val folder = File(rootDir, bookId)
        if (!folder.exists()) return@withContext

        val currentBook = loadOrInitBookMetadata(folder, forceScan = false) ?: return@withContext

        val updatedBook = currentBook.copy(
            isCoverHidden = isHidden
        )

        saveBookToJson(folder, updatedBook)

        val currentList = _allBooks.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == bookId }
        if (index != -1) {
            currentList[index] = updatedBook
            _allBooks.value = currentList
        }
    }

    suspend fun deleteBook(book: Book) = withContext(Dispatchers.IO) {
        val folder = File(book.folderPath)
        if (folder.exists()) {
            folder.deleteRecursively()
        }
        syncFileSystem()
    }

    // 북마크 토글
    suspend fun toggleBookmark(bookId: String) = withContext(Dispatchers.IO) {
        val folder = File(rootDir, bookId)
        if (!folder.exists()) return@withContext

        val currentBook = loadOrInitBookMetadata(folder, forceScan = false) ?: return@withContext
        val updatedBook = currentBook.copy(isBookmarked = !currentBook.isBookmarked)

        saveBookToJson(folder, updatedBook)

        val currentList = _allBooks.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == bookId }
        if (index != -1) {
            currentList[index] = updatedBook
        }
        // 북마크 우선 정렬
        _allBooks.value = currentList.sortedWith(
            compareByDescending<Book> { it.isBookmarked }
                .thenByDescending { it.lastModified }
        )
    }

    // 북마크되지 않은 모든 책 삭제
    suspend fun deleteAllNonBookmarkedBooks() = withContext(Dispatchers.IO) {
        val bookFolders = rootDir.listFiles()?.filter { it.isDirectory } ?: emptyList()

        for (folder in bookFolders) {
            val book = loadOrInitBookMetadata(folder, forceScan = false)
            if (book != null && !book.isBookmarked) {
                folder.deleteRecursively()
            }
        }
        syncFileSystem()
    }

    // --- 내부 헬퍼 함수 ---

    private fun loadOrInitBookMetadata(folder: File, forceScan: Boolean): Book? {
        val metaFile = File(folder, "metadata.json")
        var json: JSONObject? = null

        if (metaFile.exists()) {
            try { json = JSONObject(metaFile.readText()) } catch (e: Exception) { e.printStackTrace() }
        }

        // 1. JSON 캐시 사용
        if (!forceScan && json != null) {
            val savedCover = json.optString("coverPath", "")
            val savedCount = json.optInt("pageCount", 0)

            if (savedCover.isNotEmpty() && savedCount > 0) {
                return Book(
                    id = json.optString("id", folder.name),
                    title = json.optString("title", formatTitle(folder.name)),
                    coverPath = savedCover,
                    folderPath = folder.absolutePath,
                    pageCount = savedCount,
                    lastReadIndex = json.optInt("lastReadIndex", 0),
                    isCompleted = json.optBoolean("isCompleted", false),
                    lastModified = json.optLong("lastModified", folder.lastModified()),
                    // [변경] translationTier 로드
                    translationTier = if (json.has("translationTier")) json.getString("translationTier") else null,
                    // [추가] 표지 숨김 로드
                    isCoverHidden = json.optBoolean("isCoverHidden", false),
                    isBookmarked = json.optBoolean("isBookmarked", false)
                )
            }
        }

        // 2. 실제 파일 스캔 및 초기화
        val images = folder.listFiles { f ->
            val n = f.name.lowercase()
            n.endsWith(".jpg") || n.endsWith(".png")
        }?.sortedBy { it.name } ?: emptyList()

        val coverPath = images.firstOrNull()?.absolutePath ?: ""
        val pageCount = images.size

        val displayTitle = if (json != null && json.has("title")) {
            json.getString("title")
        } else {
            formatTitle(folder.name)
        }

        val newBook = Book(
            id = if (json != null) json.optString("id", folder.name) else folder.name,
            title = displayTitle,
            coverPath = coverPath,
            folderPath = folder.absolutePath,
            pageCount = pageCount,
            lastReadIndex = if (json != null) json.optInt("lastReadIndex", 0) else 0,
            isCompleted = if (json != null) json.optBoolean("isCompleted", false) else false,
            lastModified = folder.lastModified(),
            // [변경] translationTier 로드
            translationTier = if (json != null && json.has("translationTier")) json.getString("translationTier") else null,
            // [추가] 표지 숨김 초기값
            isCoverHidden = if (json != null) json.optBoolean("isCoverHidden", false) else false,
            isBookmarked = if (json != null) json.optBoolean("isBookmarked", false) else false
        )

        saveBookToJson(folder, newBook)
        return newBook
    }

    // 폴더명을 "MM/dd HH:mm" 형식으로 변환하는 헬퍼 함수
    private fun formatTitle(folderName: String): String {
        // "Book_20230101_120000" 형식인지 체크
        if (folderName.startsWith("Book_") && folderName.length >= 20) {
            try {
                val rawDate = folderName.substring(5) // "20230101_120000"
                val parser = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                val formatter = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
                val date = parser.parse(rawDate)
                if (date != null) {
                    return formatter.format(date)
                }
            } catch (e: Exception) {
                // 파싱 실패 시 원래 이름 반환
            }
        }
        return folderName
    }

    private fun saveBookToJson(folder: File, book: Book) {
        try {
            val json = JSONObject().apply {
                put("id", book.id)
                put("title", book.title)
                put("lastReadIndex", book.lastReadIndex)
                put("isCompleted", book.isCompleted)
                put("lastModified", book.lastModified)
                put("coverPath", book.coverPath)
                put("pageCount", book.pageCount)
                // [변경] translationTier 저장
                if (book.translationTier != null) {
                    put("translationTier", book.translationTier)
                }
                // [추가] 표지 숨김 저장
                put("isCoverHidden", book.isCoverHidden)
                // 북마크 저장
                put("isBookmarked", book.isBookmarked)
            }
            val metaFile = File(folder, "metadata.json")
            metaFile.writeText(json.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
