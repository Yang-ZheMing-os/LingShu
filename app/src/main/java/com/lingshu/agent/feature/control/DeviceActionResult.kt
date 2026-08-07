package com.lingshu.agent.feature.control

/**
 * 设备控制操作结果（模块7：手机控制）
 */
data class DeviceActionResult(
    val success: Boolean,
    val action: String,
    val message: String,
    val screenshotPath: String? = null
)
