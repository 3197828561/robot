package com.robot.solar.viewmodel

import com.robot.solar.network.mqtt.StatusMessage

object ManualControlPolicy {
    fun isAllowed(connected: Boolean, online: Boolean, status: StatusMessage?): Boolean {
        val current = status ?: return false
        return connected &&
            online &&
            current.workStatus == "stopped" &&
            current.deviceStatus == "normal" &&
            current.controlMode == "manual"
    }
}
