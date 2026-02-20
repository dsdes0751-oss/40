package com.tuna.proj_01

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object ExportHelper {

    suspend fun saveFileToDownloads(context: Context, sourceFile: File, subPath: String, fileName: String, mimeType: String): Boolean {
        return withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MangaTranslator/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val resolver = context.contentResolver
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI

                var uri: Uri? = null
                try {
                    uri = resolver.insert(collection, contentValues)
                    if (uri == null) return@withContext false

                    resolver.openOutputStream(uri)?.use { output ->
                        FileInputStream(sourceFile).use { input ->
                            input.copyTo(output)
                        }
                    }

                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                    true
                } catch (e: Exception) {
                    e.printStackTrace()
                    if (uri != null) {
                        try {
                            resolver.delete(uri, null, null)
                        } catch (ignore: Exception) {}
                    }
                    false
                }
            } else {
                // Legacy implementation for < Android 10
                try {
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val mangaDir = File(downloadsDir, "MangaTranslator")
                    val targetDir = File(mangaDir, subPath)

                    if (!targetDir.exists()) {
                        if (!targetDir.mkdirs()) return@withContext false
                    }

                    val destFile = File(targetDir, fileName)

                    FileInputStream(sourceFile).use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }

                    // MediaScanner scanning logic could be added here if needed to make it appear in galleries immediately
                    true
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }
    }
}
