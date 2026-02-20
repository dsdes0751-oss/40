package com.tuna.proj_01

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import java.io.File

class LibraryActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    private lateinit var rvBookshelf: RecyclerView
    private lateinit var tvLibraryEmpty: TextView
    private lateinit var bookAdapter: BookAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)

        rvBookshelf = findViewById(R.id.rv_bookshelf)
        tvLibraryEmpty = findViewById(R.id.tv_library_empty)
        rvBookshelf.layoutManager = GridLayoutManager(this, 3)

        bookAdapter = BookAdapter(
            onBookClick = { book -> openBookViewer(book) },
            onBookmarkClick = { book -> viewModel.toggleBookmark(book) },
            onDeleteLongPress = { book ->
                if (book.isBookmarked) {
                    Toast.makeText(this, "Remove bookmark before deleting.", Toast.LENGTH_SHORT).show()
                } else {
                    showDeleteConfirmDialog(book)
                }
            }
        )
        rvBookshelf.adapter = bookAdapter
        findViewById<Button>(R.id.btn_nav_main).setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(intent)
            finish()
        }

        initObservers()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshBookshelf()

        val viewerPrefs = getSharedPreferences("ViewerSettings", Context.MODE_PRIVATE)
        val hideAll = viewerPrefs.getBoolean("HideAllCovers", false)
        bookAdapter.isGlobalHideCover = hideAll
    }

    private fun initObservers() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.bookList.collect { books ->
                    bookAdapter.submitList(books)
                    val isEmpty = books.isEmpty()
                    tvLibraryEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
                    rvBookshelf.visibility = if (isEmpty) View.INVISIBLE else View.VISIBLE
                }
            }
        }
    }

    private fun openBookViewer(book: Book) {
        val folder = File(book.folderPath)

        val translatedTxt = File(folder, "translated.txt")
        if (translatedTxt.exists()) {
            val intent = Intent(this, NovelViewerActivity::class.java)
            intent.putExtra("book_folder_path", folder.absolutePath)
            startActivity(intent)
            return
        }

        val images = folder.listFiles { file ->
            file.name.lowercase().endsWith(".jpg") || file.name.lowercase().endsWith(".png")
        }?.sortedBy { it.name }?.map { it.toUri() } ?: emptyList()

        if (images.isNotEmpty()) {
            ImageDataHolder.setUris(images)
            val intent = Intent(this, ViewerActivity::class.java)
            intent.putStringArrayListExtra("image_uris", ArrayList(images.map { it.toString() }))
            intent.putExtra("book_folder_path", folder.absolutePath)
            intent.putExtra("start_position", book.lastReadIndex)
            startActivity(intent)
        } else {
            Toast.makeText(this, "This book has no readable content.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDeleteConfirmDialog(book: Book) {
        AlertDialog.Builder(this)
            .setTitle("Delete book")
            .setMessage("Delete '${book.title}'?")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteBook(book)
                Toast.makeText(this, "Delete되었습니다.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
