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

object MissionControlPolicy {
    fun compute(
        connected: Boolean,
        online: Boolean,
        mission: MissionState,
        manualCommandAccepted: Boolean,
        awaitingStartStatus: Boolean,
        awaitingClearEstopStatus: Boolean,
        commandInFlight: Boolean,
        retryAvailable: Boolean
    ): ControlAvailability {
        if (!connected || !online) return ControlAvailability()
        val safety = mission.safetyState
        val runState = mission.runState
        val activeMission = !mission.missionId.isNullOrBlank() &&
            runState in setOf("starting", "running", "paused")
        val safeForMission = safety == "normal"
        return ControlAvailability(
            canStart = safeForMission &&
                !activeMission &&
                mission.operationalMode == "auto" &&
                !awaitingStartStatus,
            canStop = activeMission,
            canPause = runState in setOf("starting", "running"),
            canResume = runState == "paused",
            canReplan = activeMission && mission.taskKind == "coverage",
            canEstop = safety !in setOf("estop", "clearing_estop"),
            canClearEstop = safety == "estop" && !awaitingClearEstopStatus,
            canManual = safeForMission &&
                mission.operationalMode != "manual" &&
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
        awaitingClearEstop: Boolean
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
                if (awaitingStart && runState in setOf(null, "idle", "unknown")) {
                    "启动请求已受理，等待任务状态"
                } else {
                    when (runState) {
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
