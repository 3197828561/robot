package com.robot.solar

import com.robot.solar.network.http.dto.DeviceDto
import com.robot.solar.ui.device.DeviceListFilter
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceListFilterTest {
    private val devices = listOf(
        DeviceDto("crawler_00000001", "一号清扫机器人"),
        DeviceDto("hanging_00000002", "屋顶机器人")
    )

    @Test
    fun blankQueryReturnsAllDevices() {
        assertEquals(devices, DeviceListFilter.apply(devices, "  "))
    }

    @Test
    fun queryMatchesNameOrIdIgnoringCase() {
        assertEquals(listOf(devices[0]), DeviceListFilter.apply(devices, "清扫"))
        assertEquals(listOf(devices[1]), DeviceListFilter.apply(devices, "HANGING"))
    }
}
