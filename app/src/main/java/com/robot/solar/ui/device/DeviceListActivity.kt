package com.robot.solar.ui.device

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.robot.solar.databinding.ActivityDeviceListBinding
import com.robot.solar.network.http.dto.DeviceDto
import com.robot.solar.ui.main.MainActivity
import com.robot.solar.viewmodel.DeviceListViewModel

class DeviceListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceListBinding
    private val viewModel: DeviceListViewModel by viewModels()
    private val adapter = DeviceAdapter { viewModel.selectDevice(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rvDevices.layoutManager = LinearLayoutManager(this)
        binding.rvDevices.adapter = adapter

        viewModel.devices.observe(this) { list ->
            adapter.submit(list)
            binding.tvEmpty.isVisible = list.isNullOrEmpty()
        }
        viewModel.loading.observe(this) { binding.progress.isVisible = it == true }
        viewModel.error.observe(this) { msg ->
            if (!msg.isNullOrBlank()) {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                viewModel.consumeError()
            }
        }
        viewModel.navigateMain.observe(this) { go ->
            if (go == true) {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
                viewModel.consumeNavigateMain()
            }
        }

        viewModel.loadDevices()
    }

    private class DeviceAdapter(
        private val onClick: (DeviceDto) -> Unit
    ) : RecyclerView.Adapter<DeviceAdapter.VH>() {

        private var items: List<DeviceDto> = emptyList()

        fun submit(list: List<DeviceDto>?) {
            items = list.orEmpty()
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(com.robot.solar.R.layout.item_device, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position], onClick)
        }

        override fun getItemCount(): Int = items.size

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val name: TextView = itemView.findViewById(com.robot.solar.R.id.tvDeviceName)
            private val id: TextView = itemView.findViewById(com.robot.solar.R.id.tvDeviceId)

            fun bind(item: DeviceDto, onClick: (DeviceDto) -> Unit) {
                name.text = item.displayName
                val productType = item.productType?.takeIf { it.isNotBlank() } ?: "--"
                id.text = "ID: ${item.deviceId}  类型: $productType"
                itemView.setOnClickListener { onClick(item) }
            }
        }
    }
}
