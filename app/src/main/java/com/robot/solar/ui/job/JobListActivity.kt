package com.robot.solar.ui.job

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.robot.solar.databinding.ActivityJobListBinding
import com.robot.solar.network.http.dto.JobDto
import com.robot.solar.repository.DeviceRepository
import com.robot.solar.repository.JobRepository
import kotlinx.coroutines.launch

class JobListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityJobListBinding
    private val adapter = JobAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityJobListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.rvJobs.layoutManager = LinearLayoutManager(this)
        binding.rvJobs.adapter = adapter

        val deviceId = DeviceRepository.getInstance(this).currentDeviceId()
        if (deviceId.isNullOrBlank()) {
            Toast.makeText(this, "未选择设备", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        lifecycleScope.launch {
            try {
                val jobs = JobRepository.getInstance(this@JobListActivity).fetchJobs(deviceId)
                adapter.submit(jobs)
                binding.tvEmpty.isVisible = jobs.isEmpty()
            } catch (e: Exception) {
                Toast.makeText(this@JobListActivity, "加载失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private class JobAdapter : RecyclerView.Adapter<JobAdapter.VH>() {
        private var items: List<JobDto> = emptyList()

        fun submit(list: List<JobDto>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(com.robot.solar.R.layout.item_job, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
        override fun getItemCount(): Int = items.size

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val time: TextView = v.findViewById(com.robot.solar.R.id.tvJobTime)
            private val detail: TextView = v.findViewById(com.robot.solar.R.id.tvJobDetail)

            fun bind(item: JobDto) {
                time.text = item.startedAt
                detail.text = "状态：${item.status} · 清洁 ${item.cleanedRows} 行"
            }
        }
    }
}
