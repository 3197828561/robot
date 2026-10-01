package com.robot.solar

import com.robot.solar.ui.common.ProtocolDisplayText
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolDisplayTextTest {

    @Test
    fun `mission values are translated for users`() {
        assertEquals("运行中", ProtocolDisplayText.runState("running"))
        assertEquals("正在规划路径", ProtocolDisplayText.missionPhase("planning"))
        assertEquals("跨板移动", ProtocolDisplayText.activeAction("cross_panel"))
    }

    @Test
    fun `safety and missing values are user readable`() {
        assertEquals("急停中", ProtocolDisplayText.safetyState("estop"))
        assertEquals("暂无数据", ProtocolDisplayText.safetyState(null))
        assertEquals("暂无任务", ProtocolDisplayText.runState(null))
    }

    @Test
    fun `orchestration values stay user facing`() {
        assertEquals("正在执行任务", ProtocolDisplayText.orchestrationState("running"))
        assertEquals("因安全保护已暂停", ProtocolDisplayText.orchestrationState("paused_by_safety"))
        assertEquals("正在执行内部动作", ProtocolDisplayText.orchestrationState("running_child"))
    }
}
