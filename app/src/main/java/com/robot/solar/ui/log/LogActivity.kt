package com.robot.solar.ui.log

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.robot.solar.databinding.ActivityLogBinding
import com.robot.solar.entity.LogFilter
import com.robot.solar.entity.StructuredLogEntity
import com.robot.solar.viewmodel.LogListViewModel
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding
    private val viewModel: LogListViewModel by viewModels()
    private val adapter = LogListAdapter(::showDetails)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "APP 结构化日志"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.btnClearLogs.setOnClickListener { confirmClearLogs() }

        binding.rvLogs.layoutManager = LinearLayoutManager(this)
        binding.rvLogs.adapter = adapter

        binding.filterGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            viewModel.setFilter(
                when (checkedId) {
                    binding.btnFilterOperations.id -> LogFilter.OPERATIONS
                    binding.btnFilterDevice.id -> LogFilter.DEVICE
                    binding.btnFilterConnection.id -> LogFilter.CONNECTION
                    binding.btnFilterErrors.id -> LogFilter.ERRORS
                    else -> LogFilter.ALL
                }
            )
        }
        binding.etLogSearch.doAfterTextChanged { viewModel.setQuery(it?.toString().orEmpty()) }

        viewModel.logs.observe(this) { list ->
            adapter.submitList(list)
            val empty = list.isNullOrEmpty()
            binding.tvEmpty.isVisible = empty
            binding.rvLogs.isVisible = !empty
            binding.tvLogSummary.text = if (empty) {
                "当前筛选条件下没有日志"
            } else {
                "共 ${list.size} 条 · 点击日志查看结构化详情"
            }
            binding.swipeRefresh.isRefreshing = false
        }

        binding.swipeRefresh.setOnRefreshListener {
            binding.swipeRefresh.isRefreshing = false
        }
    }

    private fun showDetails(item: StructuredLogEntity) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA)
            .format(Date(item.timestampMillis))
        val detail = buildString {
            appendLine("时间：$time")
            appendLine("级别：${item.severity.displayName}")
            appendLine("分类：${item.category.displayName}")
            appendLine("来源：${item.source.displayName}")
            appendLine("事件：${item.eventType}")
            item.deviceId?.let { appendLine("设备：$it") }
            item.topic?.let { appendLine("Topic：$it") }
            item.cmdId?.let { appendLine("cmdId：$it") }
            item.missionId?.let { appendLine("missionId：$it") }
            item.action?.let { appendLine("操作：$it") }
            item.result?.let { appendLine("结果：$it") }
            appendLine()
            appendLine(item.summary)
            item.detailJson?.takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine(prettyJson(it))
            }
        }.trim()
        MaterialAlertDialogBuilder(this)
            .setTitle("日志详情")
            .setMessage(detail)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun confirmClearLogs() {
        MaterialAlertDialogBuilder(this)
            .setTitle("清空本地日志")
            .setMessage("将删除此 APP 在当前设备上保存的全部日志，此操作不可恢复。不会影响机器人或云服务器数据。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                viewModel.clearAll()
                Toast.makeText(this, "本地日志已清空", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun prettyJson(raw: String): String =
        runCatching { JSONObject(raw).toString(2) }.getOrDefault(raw)

    private fun applySystemBarInsets() {
        val initialLeft = binding.root.paddingLeft
        val initialTop = binding.root.paddingTop
        val initialRight = binding.root.paddingRight
        val initialBottom = binding.root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left = initialLeft + bars.left,
                top = initialTop + bars.top,
                right = initialRight + bars.right,
                bottom = initialBottom + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }
}
