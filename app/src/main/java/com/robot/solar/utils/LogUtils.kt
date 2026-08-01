package com.robot.solar.utils

import com.robot.solar.entity.LogCategory
import com.robot.solar.entity.LogDirection
import com.robot.solar.entity.LogSeverity
import com.robot.solar.entity.LogSource
import com.robot.solar.entity.StructuredLogDraft
import com.robot.solar.repository.LogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

object LogUtils {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = Channel<StructuredLogDraft>(Channel.UNLIMITED)
    @Volatile
    private var repository: LogRepository? = null

    init {
        appScope.launch {
            for (draft in pending) {
                try {
                    repository?.upsert(draft)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun init(repository: LogRepository) {
        this.repository = repository
    }

    fun record(draft: StructuredLogDraft) {
        pending.trySend(draft)
    }

    fun login(content: String) = record(
        basic(LogCategory.AUTH, "auth_event", content)
    )

    fun device(content: String) = record(
        basic(LogCategory.DEVICE, "device_event", content, source = LogSource.ROBOT)
    )

    fun system(content: String) = record(
        basic(LogCategory.SYSTEM, "system_event", content)
    )

    fun connection(
        eventType: String,
        summary: String,
        result: String? = null,
        severity: LogSeverity = LogSeverity.INFO,
        detailJson: String? = null
    ) = record(
        StructuredLogDraft(
            source = LogSource.MQTT,
            category = LogCategory.CONNECTION,
            eventType = eventType,
            severity = severity,
            result = result,
            summary = summary,
            detailJson = detailJson
        )
    )

    fun command(
        cmdId: String,
        action: String?,
        summary: String,
        result: String,
        paramsSummary: String? = null,
        missionId: String? = null,
        severity: LogSeverity = LogSeverity.INFO,
        detailJson: String? = null
    ) = record(
        StructuredLogDraft(
            eventId = "command:$cmdId",
            source = LogSource.APP,
            category = LogCategory.COMMAND,
            eventType = "command",
            severity = severity,
            direction = LogDirection.DOWNSTREAM,
            topic = "cmd",
            cmdId = cmdId,
            missionId = missionId,
            action = action,
            result = result,
            summary = summary,
            detailJson = detailJson ?: paramsSummary?.let(::detailWithParams)
        )
    )

    fun remote(
        eventType: String,
        summary: String,
        result: String? = null,
        detailJson: String? = null,
        severity: LogSeverity = LogSeverity.INFO
    ) = record(
        StructuredLogDraft(
            source = LogSource.APP,
            category = LogCategory.REMOTE,
            eventType = eventType,
            severity = severity,
            direction = LogDirection.DOWNSTREAM,
            topic = "remote",
            result = result,
            summary = summary,
            detailJson = detailJson
        )
    )

    private fun basic(
        category: LogCategory,
        eventType: String,
        summary: String,
        source: LogSource = LogSource.APP
    ) = StructuredLogDraft(
        source = source,
        category = category,
        eventType = eventType,
        summary = summary
    )

    private fun detailWithParams(params: String): String {
        val escaped = params
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
        return "{\"params\":\"$escaped\"}"
    }
}
