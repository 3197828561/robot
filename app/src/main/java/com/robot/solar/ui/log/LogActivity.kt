package com.robot.solar.ui.log

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.robot.solar.databinding.ActivityLogBinding
import com.robot.solar.utils.LogUtils
import com.robot.solar.viewmodel.LogListViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding
    private val viewModel: LogListViewModel by viewModels()
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val adapter = LogListAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.rvLogs.layoutManager = LinearLayoutManager(this)
        binding.rvLogs.adapter = adapter

        viewModel.logs.observe(this) { list ->
            adapter.submitList(list)
            val empty = list.isNullOrEmpty()
            binding.tvEmpty.isVisible = empty
            binding.rvLogs.isVisible = !empty
        }

        binding.swipeRefresh.setOnRefreshListener {
            uiScope.launch {
                LogUtils.system("刷新日志列表")
                delay(350)
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }
}
