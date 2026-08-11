package com.lingshu.feature.control.domain

import com.lingshu.core.common.error.Result

interface ISystemControl {
    suspend fun setWifi(on: Boolean): Result<Unit>
    suspend fun setBluetooth(on: Boolean): Result<Unit>
    suspend fun setFlashlight(on: Boolean): Result<Unit>
    suspend fun setVolume(level: Int): Result<Unit>
    suspend fun setBrightness(level: Int): Result<Unit>
    suspend fun setAutoRotate(on: Boolean): Result<Unit>
    suspend fun openApp(packageName: String): Result<Unit>
    suspend fun closeApp(packageName: String): Result<Unit>
    suspend fun takeScreenshot(): Result<Unit>
}
