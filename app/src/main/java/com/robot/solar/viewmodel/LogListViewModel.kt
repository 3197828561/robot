package com.robot.solar.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.asLiveData
import com.robot.solar.repository.LogRepository

class LogListViewModel(application: Application) : AndroidViewModel(application) {
    private val logRepository = LogRepository.getInstance(application)
    val logs = logRepository.observeLogsDesc().asLiveData()
}
