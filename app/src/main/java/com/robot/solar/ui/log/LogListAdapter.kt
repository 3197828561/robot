package com.robot.solar.ui.log

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.robot.solar.R
import com.robot.solar.databinding.ItemLogBinding
import com.robot.solar.entity.LogSeverity
import com.robot.solar.entity.StructuredLogEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LOG_DIFF = object : DiffUtil.ItemCallback<StructuredLogEntity>() {
    override fun areItemsTheSame(oldItem: StructuredLogEntity, newItem: StructuredLogEntity): Boolean =
        oldItem.eventId == newItem.eventId

    override fun areContentsTheSame(oldItem: StructuredLogEntity, newItem: StructuredLogEntity): Boolean =
        oldItem == newItem
}

class LogListAdapter(
    private val onClick: (StructuredLogEntity) -> Unit
) : ListAdapter<StructuredLogEntity, LogListAdapter.LogViewHolder>(LOG_DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val binding = ItemLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return LogViewHolder(binding, onClick)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class LogViewHolder(
        private val binding: ItemLogBinding,
        private val onClick: (StructuredLogEntity) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

        fun bind(item: StructuredLogEntity) {
            binding.tvLogSeverity.text = item.severity.displayName
            binding.tvLogSeverity.setTextColor(
                ContextCompat.getColor(
                    binding.root.context,
                    when (item.severity) {
                        LogSeverity.DEBUG -> R.color.control_text_muted
                        LogSeverity.INFO -> R.color.control_primary
                        LogSeverity.WARNING -> R.color.control_warning
                        LogSeverity.ERROR, LogSeverity.CRITICAL -> R.color.control_danger
                    }
                )
            )
            binding.tvLogCategory.text = item.category.displayName
            binding.tvLogTime.text = sdf.format(Date(item.timestampMillis))
            binding.tvLogSummary.text = item.summary
            binding.tvLogMetadata.text = buildList {
                item.deviceId?.let { add("设备 $it") }
                item.action?.let { add("操作 $it") }
                item.result?.let { add("结果 $it") }
                item.cmdId?.let { add("cmdId $it") }
                if (item.repeatCount > 1) add("重复 ${item.repeatCount} 次")
            }.joinToString(" · ").ifBlank { "${item.source.displayName} · ${item.eventType}" }
            binding.root.setOnClickListener { onClick(item) }
        }
    }
}
