package com.robot.solar.viewmodel

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
