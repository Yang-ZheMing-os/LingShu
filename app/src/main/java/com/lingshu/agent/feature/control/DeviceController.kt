package com.lingshu.agent.feature.control

import android.app.Application

data class DeviceActionResult(val success: Boolean = true, val message: String = "")

class DeviceController(private val app: Application) {
    fun execute(command: String): DeviceActionResult = DeviceActionResult()
}

