package com.robot.solar.demo.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.asLiveData
import com.robot.solar.demo.repository.LogRepository

/**
 * 日志列表 ViewModel：以 LiveData 形式暴露按时间倒序的 Room 数据流
 */
class LogListViewModel(application: Application) : AndroidViewModel(application) {

    private val logRepository = LogRepository.getInstance(application)

    val logs = logRepository.observeLogsDesc().asLiveData()
}
