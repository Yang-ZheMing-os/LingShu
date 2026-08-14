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
    suspend fun setAirplaneMode(on: Boolean): Result<Unit>

    /** 根据 App 中文名解析包名，未匹配返回空字符串 */
    fun getPackageNameByAppName(appName: String): String

    /** 通过 deeplink 打开指定应用，packageName 为目标包名，deeplinkUri 为跳转协议 */
    suspend fun openAppWithDeepLink(packageName: String, deeplinkUri: String): Result<Unit>

    /** 导航到指定目的地，优先高德、其次百度、最后 geo: 兜底 */
    suspend fun navigateToMap(destination: String): Result<Unit>

    /** 打开外卖应用（美团外卖/饿了么） */
    suspend fun openTakeout(): Result<Unit>
}
