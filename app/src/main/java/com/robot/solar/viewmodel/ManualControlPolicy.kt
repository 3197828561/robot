package com.robot.solar.viewmodel

import com.robot.solar.network.mqtt.MissionState

object ManualControlPolicy {
    fun isAllowed(
        connected: Boolean,
        online: Boolean,
        operationalMode: String?,
        safetyState: String?,
        manualCommandAccepted: Boolean
    ): Boolean {
        return connected &&
            online &&
            operationalMode == "manual" &&
            safetyState == "normal" &&
            manualCommandAccepted
    }
}

object RemotePageLifecyclePolicy {
    /** Activity失焦只应中止手动页遥控，普通页面跳转不发布零速。 */
    fun shouldStopOnPause(isRemotePage: Boolean): Boolean = isRemotePage
}

object MissionControlPolicy {
    fun compute(
        connected: Boolean,
        online: Boolean,
        mission: MissionState,
        manualCommandAccepted: Boolean,
        awaitingStartStatus: Boolean,
        awaitingClearEstopStatus: Boolean,
        commandInFlight: Boolean,
        retryAvailable: Boolean,
        debugBypass: Boolean = false
    ): ControlAvailability {
        if (!connected || !online) return ControlAvailability()
        if (debugBypass) {
            val manualReady = ManualControlPolicy.isAllowed(
                connected,
                online,
                mission.operationalMode,
                mission.safetyState,
                manualCommandAccepted
            )
            return ControlAvailability(
                canStart = true,
                canStop = true,
                canPause = true,
                canResume = true,
                canReplan = true,
                canEstop = true,
                canClearEstop = true,
                canManual = true,
                canAuto = manualReady,
                canRemote = manualReady,
                canRetry = retryAvailable
            )
        }
        val safety = mission.safetyState
        val runState = mission.runState
        val activeMission = !mission.controlMissionId.isNullOrBlank() && when (mission.orchestrationState) {
            "running", "paused_by_user", "paused_by_safety", "running_child", "resuming" -> true
            null, "unknown" -> runState in setOf("starting", "running", "paused")
            else -> false
        }
        val rootCoverage = mission.rootTaskKind == "coverage"
        val rootOnly = when {
            !mission.rootMissionId.isNullOrBlank() -> mission.taskStackDepth == 1
            else -> mission.taskStackDepth == null
        }
        val safeForMission = safety == "normal"
        return ControlAvailability(
            canStart = safeForMission &&
                !activeMission &&
                mission.operationalMode == "auto" &&
                !awaitingStartStatus,
            canStop = activeMission,
            canPause = activeMission && runState in setOf("starting", "running"),
            canResume = activeMission && runState == "paused",
            canReplan = activeMission && rootCoverage && rootOnly,
            canEstop = safety !in setOf("estop", "clearing_estop"),
            canClearEstop = safety == "estop" && !awaitingClearEstopStatus,
            canManual = safeForMission &&
                !manualCommandAccepted &&
                !commandInFlight,
            canAuto = mission.operationalMode == "manual" && !commandInFlight,
            canRemote = ManualControlPolicy.isAllowed(
                connected,
                online,
                mission.operationalMode,
                mission.safetyState,
                manualCommandAccepted
            ),
            canRetry = retryAvailable && !commandInFlight
        )
    }
}

object MissionStatusDisplay {
    fun text(
        runState: String?,
        safetyState: String?,
        awaitingStart: Boolean,
        awaitingClearEstop: Boolean,
        orchestrationState: String? = null,
        taskStackDepth: Int? = null,
        interruptionReason: String? = null
    ): String {
        return when (safetyState) {
            "estop" -> if (awaitingClearEstop) {
                "解除急停请求已受理，等待安全状态更新"
            } else {
                "急停"
            }
            "clearing_estop" -> "解除急停中"
            "low_battery" -> "低电量"
            "fault" -> "故障"
            else -> {
                if (
                    awaitingStart &&
                    orchestrationState in setOf(null, "idle", "unknown") &&
                    runState in setOf(null, "idle", "unknown")
                ) {
                    "启动请求已受理，等待任务状态"
                } else {
                    when (orchestrationState) {
                        "idle" -> "空闲"
                        "running" -> "根任务运行中"
                        "paused_by_user" -> "根任务已由用户暂停"
                        "paused_by_safety" -> "根任务因安全原因暂停"
                        "running_child" -> buildString {
                            append("根任务已中断，正在执行内部子任务")
                            interruptionReason?.takeIf { it.isNotBlank() }?.let {
                                append("（${if (it == "LOW_BATTERY") "低电量" else it}）")
                            }
                            if (taskStackDepth != null) append("，栈深 $taskStackDepth")
                        }
                        "resuming" -> "内部子任务结束，正在恢复根任务"
                        "succeeded" -> "根任务已完成"
                        "failed" -> "根任务失败"
                        "canceled" -> "根任务已取消"
                        "unknown" -> "根任务状态未知"
                        else -> when (runState) {
                            "idle" -> "空闲"
                            "starting" -> "启动中"
                            "running" -> "运行中"
                            "paused" -> "已暂停"
                            "succeeded" -> "已完成"
                            "failed" -> "失败"
                            "canceled" -> "已取消"
                            null -> if (awaitingStart) "启动请求已受理，等待任务状态" else "--"
                            else -> "未知"
                        }
                    }
                }
            }
        }
    }
}

object MissionCommandErrorDisplay {
    fun text(errorCode: String?): String? = when (errorCode) {
        null, "" -> null
        "INVALID_PAYLOAD" -> "命令参数不符合接口要求"
        "UNSUPPORTED_VERSION" -> "Robot 不支持当前接口版本"
        "DEVICE_MISMATCH" -> "命令设备与 Robot 不匹配"
        "UNSUPPORTED_CMD" -> "Robot 不支持该命令"
        "MISSION_SERVICE_UNAVAILABLE" -> "任务服务不可用"
        "MISSION_SERVICE_TIMEOUT" -> "任务服务响应超时"
        "MISSION_SERVICE_ERROR" -> "任务服务调用失败"
        "MISSION_INVALID_COMMAND" -> "任务命令无效"
        "MISSION_INVALID_REQUEST" -> "任务请求参数无效"
        "MISSION_BUSY" -> "Robot 当前有任务正在处理"
        "MISSION_NOT_FOUND" -> "目标任务不存在或已失效"
        "MISSION_ILLEGAL_STATE" -> "当前任务状态不允许执行该操作"
        "MISSION_INTERNAL_ERROR" -> "任务模块内部错误"
        "MISSION_REJECTED" -> "任务层拒绝了该操作"
        else -> "Robot 返回错误"
    }?.let { "$it（$errorCode）" }
}
