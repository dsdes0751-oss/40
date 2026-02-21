package com.tuna.proj_01

import android.app.Activity
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class BookExportResult(
    val successCount: Int,
    val isNovel: Boolean
)

object BookFileManager {

    suspend fun readBookTitle(folder: File): String = withContext(Dispatchers.IO) {
        val meta = File(folder, "metadata.json")
        if (!meta.exists()) return@withContext folder.name
        return@withContext try {
            val json = JSONObject(meta.readText())
            json.optString("title", folder.name).ifBlank { folder.name }
        } catch (_: Exception) {
            folder.name
        }
    }

    suspend fun exportCurrentBook(context: Activity, folder: File): BookExportResult = withContext(Dispatchers.IO) {
        val translated = File(folder, "translated.txt")
        if (translated.exists()) {
            val title = readBookTitle(folder)
            val safe = sanitize(title).ifBlank { folder.name }
            val success = ExportHelper.saveFileToDownloads(
                context,
                translated,
                "Novels",
                "$safe.txt",
                "text/plain"
            )
            return@withContext BookExportResult(if (success) 1 else 0, isNovel = true)
        }

        val images = folder.listFiles { f ->
            val n = f.name.lowercase()
            n.endsWith(".jpg") || n.endsWith(".png")
        }?.sortedBy { it.name } ?: emptyList()

        var successCount = 0
        for (image in images) {
            if (ExportHelper.saveFileToDownloads(context, image, "Manga", image.name, "image/jpeg")) {
                successCount++
            }
        }
        BookExportResult(successCount, isNovel = false)
    }

    fun shareTextBook(activity: Activity, title: String, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        activity.startActivity(Intent.createChooser(intent, null))
    }

    suspend fun deleteCurrentBook(folder: File): Boolean = withContext(Dispatchers.IO) {
        if (!folder.exists()) return@withContext false
        folder.deleteRecursively()
    }

    suspend fun deleteAllBooks(rootDir: File, keepBookmarked: Boolean): Int = withContext(Dispatchers.IO) {
        if (!rootDir.exists()) return@withContext 0
        var deleted = 0
        val folders = rootDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        for (folder in folders) {
            if (keepBookmarked) {
                val metadata = BookMetadataManager.loadMetadata(folder)
                if (metadata.isBookmarked) continue
            }
            if (folder.deleteRecursively()) {
                deleted++
            }
        }
        deleted
    }

    private fun sanitize(name: String): String {
        return name.replace(Regex("[^\\p{L}\\p{N}\\-_ ]"), "").trim()
    }
}
