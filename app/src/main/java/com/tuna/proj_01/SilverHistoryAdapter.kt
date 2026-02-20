package com.tuna.proj_01

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SilverHistory(
    val id: String,
    val amount: Long,
    val description: String,
    val timestamp: Long
)

class SilverHistoryAdapter : ListAdapter<SilverHistory, SilverHistoryAdapter.ViewHolder>(DiffCallback) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tv_history_title)
        val tvDate: TextView = view.findViewById(R.id.tv_history_date)
        val tvAmount: TextView = view.findViewById(R.id.tv_history_amount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_silver_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.tvTitle.text = item.description

        val date = Date(item.timestamp)
        val format = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.getDefault())
        holder.tvDate.text = format.format(date)

        if (item.amount > 0) {
            holder.tvAmount.text = "+${item.amount}"
            holder.tvAmount.setTextColor(Color.parseColor("#4CAF50")) // Green
        } else {
            holder.tvAmount.text = "${item.amount}"
            holder.tvAmount.setTextColor(Color.parseColor("#FF5252")) // Red
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<SilverHistory>() {
        override fun areItemsTheSame(oldItem: SilverHistory, newItem: SilverHistory) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: SilverHistory, newItem: SilverHistory) = oldItem == newItem
    }
}