package com.tuna.proj_01

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.signature.ObjectKey
import java.io.File

class BookAdapter(
    private val onBookClick: (Book) -> Unit,
    private val onBookmarkClick: (Book) -> Unit,
    private val onDeleteLongPress: (Book) -> Unit
) : ListAdapter<Book, BookAdapter.BookViewHolder>(BookDiffCallback) {

    var isGlobalHideCover: Boolean = false
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    companion object BookDiffCallback : DiffUtil.ItemCallback<Book>() {
        override fun areItemsTheSame(oldItem: Book, newItem: Book): Boolean = oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Book, newItem: Book): Boolean = oldItem == newItem
    }

    class BookViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivCover: ImageView = view.findViewById(R.id.iv_book_cover)
        val tvTitle: TextView = view.findViewById(R.id.tv_book_title)
        val tvCount: TextView = view.findViewById(R.id.tv_book_count)
        val btnBookmark: ImageButton = view.findViewById(R.id.btn_bookmark)
        val tvStatus: TextView? = view.findViewById(R.id.tv_status_badge)
        val viewTierIndicator: View? = view.findViewById(R.id.view_tier_indicator)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_book, parent, false)
        return BookViewHolder(view)
    }

    override fun onBindViewHolder(holder: BookViewHolder, position: Int) {
        val book = getItem(position)
        val context = holder.itemView.context

        holder.tvTitle.text = book.title
        holder.tvCount.text = context.getString(R.string.book_pages_format, book.pageCount)

        holder.tvStatus?.let { tv ->
            if (book.lastReadIndex == 0 && !book.isCompleted) {
                tv.visibility = View.VISIBLE
                tv.text = context.getString(R.string.book_new_badge)
                tv.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.status_new_badge_text))
            } else {
                tv.visibility = View.GONE
            }
        }

        holder.viewTierIndicator?.let { view ->
            val tierColorRes = when (book.translationTier) {
                "STANDARD" -> R.color.tier_standard
                "ADVANCED" -> R.color.tier_advanced
                "PRO" -> R.color.tier_pro
                else -> android.R.color.transparent
            }
            view.setBackgroundColor(ContextCompat.getColor(holder.itemView.context, tierColorRes))
            view.visibility = if (book.translationTier != null) View.VISIBLE else View.INVISIBLE
        }

        if (isGlobalHideCover || book.isCoverHidden) {
            val coverPlaceholder = ContextCompat.getColor(holder.itemView.context, R.color.bg_surface_alt)
            holder.ivCover.setImageDrawable(coverPlaceholder.toDrawable())
        } else {
            Glide.with(holder.itemView.context)
                .load(File(book.coverPath))
                .signature(ObjectKey(book.lastModified))
                .thumbnail(0.1f)
                .transition(DrawableTransitionOptions.withCrossFade())
                .placeholder(android.R.drawable.ic_menu_gallery)
                .into(holder.ivCover)
        }

        holder.btnBookmark.setImageResource(
            if (book.isBookmarked) android.R.drawable.btn_star_big_on
            else android.R.drawable.btn_star_big_off
        )

        holder.itemView.setOnClickListener { onBookClick(book) }
        holder.btnBookmark.setOnClickListener { onBookmarkClick(book) }
        holder.itemView.setOnLongClickListener {
            onDeleteLongPress(book)
            true
        }
    }
}
