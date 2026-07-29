package com.robot.solar.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.robot.solar.entity.LogFilter
import com.robot.solar.logging.AppLogPolicy
import com.robot.solar.repository.LogRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class LogListViewModel(application: Application) : AndroidViewModel(application) {
    private val logRepository = LogRepository.getInstance(application)
    private val filter = MutableStateFlow(LogFilter.ALL)
    private val query = MutableStateFlow("")

    val logs = combine(logRepository.observeLogsDesc(), filter, query) { items, selected, text ->
        items.filter {
            AppLogPolicy.matchesFilter(it, selected) && AppLogPolicy.matchesQuery(it, text)
        }
    }.asLiveData()

    fun setFilter(value: LogFilter) {
        filter.value = value
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun clearAll() {
        viewModelScope.launch {
            logRepository.clearAll()
        }
    }
}
