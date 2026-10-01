package com.robot.solar.ui.device

import com.robot.solar.network.http.dto.DeviceDto

internal object DeviceListFilter {
    fun apply(devices: List<DeviceDto>, query: String): List<DeviceDto> {
        val keyword = query.trim()
        if (keyword.isEmpty()) return devices
        return devices.filter { device ->
            device.displayName.contains(keyword, ignoreCase = true) ||
                device.deviceId.contains(keyword, ignoreCase = true)
        }
    }
}
