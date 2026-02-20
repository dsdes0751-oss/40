package com.tuna.proj_01

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SilverHistory(
    val id: String,
    val title: String,
    val subtitle: String,
    val timestamp: Long,
    val amountText: String,
    val amountKind: AmountKind
)

enum class AmountKind {
    POSITIVE,
    NEGATIVE,
    NEUTRAL
}

class SilverHistoryAdapter : ListAdapter<SilverHistory, SilverHistoryAdapter.ViewHolder>(DiffCallback) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tv_history_title)
        val tvSubtitle: TextView = view.findViewById(R.id.tv_history_date)
        val tvMeta: TextView = view.findViewById(R.id.tv_history_meta)
        val tvAmount: TextView = view.findViewById(R.id.tv_history_amount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_silver_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.tvTitle.text = item.title
        holder.tvSubtitle.text = item.subtitle

        val date = Date(item.timestamp)
        val format = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.getDefault())
        holder.tvMeta.text = format.format(date)

        holder.tvAmount.text = item.amountText
        when (item.amountKind) {
            AmountKind.POSITIVE -> {
                holder.tvAmount.setTextColor(
                    ContextCompat.getColor(holder.itemView.context, R.color.status_success)
                )
            }

            AmountKind.NEGATIVE -> {
                holder.tvAmount.setTextColor(
                    ContextCompat.getColor(holder.itemView.context, R.color.status_error)
                )
            }

            AmountKind.NEUTRAL -> {
                holder.tvAmount.setTextColor(
                    ContextCompat.getColor(holder.itemView.context, R.color.text_secondary)
                )
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<SilverHistory>() {
        override fun areItemsTheSame(oldItem: SilverHistory, newItem: SilverHistory) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: SilverHistory, newItem: SilverHistory) = oldItem == newItem
    }
}
