package com.robot.solar.viewmodel

object ManualControlPolicy {
    fun isAllowed(connected: Boolean, online: Boolean): Boolean {
        return connected && online
    }
}
