package com.robot.solar.repository

import com.robot.solar.network.http.dto.DeviceDto

internal object DeviceIdentityPolicy {
    private val supportedProductTypes = setOf("crawler", "hanging", "installer")

    fun effectiveProductType(device: DeviceDto): String? {
        val prefix = device.deviceId.substringBefore("_", missingDelimiterValue = "")
        val declared = device.productType?.trim().orEmpty()
        if (prefix !in supportedProductTypes) return null
        if (declared.isNotEmpty() && declared != prefix) return null
        return declared.ifEmpty { prefix }
    }

    fun isSupported(device: DeviceDto): Boolean = effectiveProductType(device) != null
}
