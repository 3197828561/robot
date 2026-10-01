package com.robot.solar

import com.robot.solar.network.http.dto.DeviceDto
import com.robot.solar.repository.DeviceIdentityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdentityPolicyTest {
    @Test
    fun missingProductTypeFallsBackToSupportedDevicePrefix() {
        val device = DeviceDto("crawler_00000001", "测试机器人")

        assertTrue(DeviceIdentityPolicy.isSupported(device))
        assertEquals("crawler", DeviceIdentityPolicy.effectiveProductType(device))
    }

    @Test
    fun legacyUnknownDeviceIsRejected() {
        assertFalse(DeviceIdentityPolicy.isSupported(DeviceDto("rk3588", "旧设备")))
    }

    @Test
    fun declaredProductTypeMustMatchDevicePrefix() {
        val device = DeviceDto("crawler_00000001", "测试机器人", productType = "hanging")

        assertFalse(DeviceIdentityPolicy.isSupported(device))
    }
}
