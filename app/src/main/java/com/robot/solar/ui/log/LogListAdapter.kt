package com.robot.solar.ui.log

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.robot.solar.databinding.ItemLogBinding
import com.robot.solar.entity.SolarLogEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LOG_DIFF = object : DiffUtil.ItemCallback<SolarLogEntity>() {
    override fun areItemsTheSame(oldItem: SolarLogEntity, newItem: SolarLogEntity): Boolean =
        oldItem.id == newItem.id

    override fun areContentsTheSame(oldItem: SolarLogEntity, newItem: SolarLogEntity): Boolean =
        oldItem == newItem
}

class LogListAdapter : ListAdapter<SolarLogEntity, LogListAdapter.LogViewHolder>(LOG_DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val binding = ItemLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return LogViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class LogViewHolder(private val binding: ItemLogBinding) : RecyclerView.ViewHolder(binding.root) {
        private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

        fun bind(item: SolarLogEntity) {
            binding.tvLogType.text = item.type.displayName
            binding.tvLogTime.text = sdf.format(Date(item.timestampMillis))
            binding.tvLogContent.text = item.content
        }
    }
}
