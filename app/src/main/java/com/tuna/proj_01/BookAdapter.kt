package com.tuna.proj_01

import android.annotation.SuppressLint
import android.graphics.Color

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.signature.ObjectKey
import java.io.File
import androidx.core.graphics.toColorInt
import androidx.core.graphics.drawable.toDrawable

/**
 * [변경 사항]
 * 1. RecyclerView.Adapter -> ListAdapter 변경 (DiffUtil 자동 적용)
 * 2. notifyDataSetChanged() 제거 -> submitList() 사용
 * 3. lastModified(타임스탬프) 비교를 통해 표지 변경 시에만 UI 부분 갱신
 * 4. 삭제 버튼 -> 북마크 토글 버튼으로 변경, 삭제는 롱프레스로 처리
 */
class BookAdapter(
    private val onBookClick: (Book) -> Unit,
    private val onBookmarkClick: (Book) -> Unit,
    private val onDeleteLongPress: (Book) -> Unit
) : ListAdapter<Book, BookAdapter.BookViewHolder>(BookDiffCallback) {

    // [추가] 표지 숨김 전역 설정
    var isGlobalHideCover: Boolean = false
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    // DiffUtil: 데이터가 변경되었는지 판단하는 핵심 로직
    companion object BookDiffCallback : DiffUtil.ItemCallback<Book>() {
        override fun areItemsTheSame(oldItem: Book, newItem: Book): Boolean {
            // ID(폴더명)가 같으면 같은 아이템으로 간주
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Book, newItem: Book): Boolean {
            // [핵심] 모든 필드가 같아야 같은 콘텐츠로 간주.
            // 특히 'lastModified'가 변하면(번역 이미지가 갱신되면) false가 되어 onBindViewHolder가 다시 호출됨 -> 표지 갱신
            return oldItem == newItem
        }
    }

    class BookViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivCover: ImageView = view.findViewById(R.id.iv_book_cover)
        val tvTitle: TextView = view.findViewById(R.id.tv_book_title)
        val tvCount: TextView = view.findViewById(R.id.tv_book_count)
        val btnBookmark: ImageButton = view.findViewById(R.id.btn_bookmark)
        val tvStatus: TextView? = view.findViewById(R.id.tv_status_badge)
        val viewTierIndicator: View? = view.findViewById(R.id.view_tier_indicator) // [추가]
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_book, parent, false)
        return BookViewHolder(view)
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: BookViewHolder, position: Int) {
        val book = getItem(position)

        holder.tvTitle.text = book.title

        // [변경] 진척도 퍼센트 제거
        holder.tvCount.text = "${book.pageCount} Pages"

        // [변경] 상태 뱃지 → "New"만 유지
        holder.tvStatus?.let { tv ->
            if (book.lastReadIndex == 0 && !book.isCompleted) {
                tv.visibility = View.VISIBLE
                tv.text = "New"
                tv.setTextColor("#2196F3".toColorInt())
            } else {
                tv.visibility = View.GONE
            }
        }

        // [추가] 번역 등급 표시기 색상 설정
        holder.viewTierIndicator?.let { view ->
            val tierColor = when(book.translationTier) {
                "STANDARD" -> "#9E9E9E".toColorInt() // Grey
                "ADVANCED" -> "#B0BEC5".toColorInt() // Silver-ish
                "PRO" -> "#FFD700".toColorInt() // Gold
                else -> Color.TRANSPARENT
            }
            view.setBackgroundColor(tierColor)
            // 등급 정보가 없으면 투명하게
            view.visibility = if (book.translationTier != null) View.VISIBLE else View.INVISIBLE
        }

        // [수정] 표지 숨김 처리
        if (isGlobalHideCover || book.isCoverHidden) {
            holder.ivCover.setImageDrawable(Color.BLACK.toDrawable())
        } else {
            // [최적화] Glide 로딩
            Glide.with(holder.itemView.context)
                .load(File(book.coverPath))
                .signature(ObjectKey(book.lastModified))
                .thumbnail(0.1f)
                .transition(DrawableTransitionOptions.withCrossFade())
                .placeholder(android.R.drawable.ic_menu_gallery)
                .into(holder.ivCover)
        }

        // 북마크 아이콘 설정
        holder.btnBookmark.setImageResource(
            if (book.isBookmarked) android.R.drawable.btn_star_big_on
            else android.R.drawable.btn_star_big_off
        )

        holder.itemView.setOnClickListener { onBookClick(book) }
        holder.btnBookmark.setOnClickListener { onBookmarkClick(book) }

        // 롱프레스로 삭제
        holder.itemView.setOnLongClickListener {
            onDeleteLongPress(book)
            true
        }
    }
}
