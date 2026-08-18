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

    /** 网页搜索 */
    suspend fun webSearch(query: String): Result<Unit>

    /** 播放音乐 */
    suspend fun playMusic(): Result<Unit>

    /** 设置闹钟 */
    suspend fun setAlarm(hour: Int?, minute: Int, label: String?): Result<Unit>

    /** 打开相机 */
    suspend fun openCamera(): Result<Unit>

    /** 拨打电话 */
    suspend fun makeCall(phoneNumberOrContact: String): Result<Unit>

    /** 发送短信 */
    suspend fun sendSms(phoneNumberOrContact: String, message: String): Result<Unit>

    // =========================================================================
    //  三大复合场景（安全合规版：只做打开+预填，不做自动确认/支付/发送）
    // =========================================================================

    /**
     * 点外卖：
     *  1. 优先打开已安装的美团外卖/饿了么 App
     *  2. 通过 deeplink 或主界面搜索框预填菜品/商家关键词
     *  3. ⚠️ 绝不自动下单或支付，这一步必须留给用户人工点击
     */
    suspend fun orderTakeout(
        foodKeyword: String? = null,
        restaurant: String? = null,
        addressHint: String? = null
    ): Result<Unit>

    /**
     * 给指定联系人发消息（微信/QQ/短信渠道）：
     *  1. 通过 ContentResolver 查询联系人姓名/昵称匹配到手机号（需要 READ_CONTACTS 权限）
     *  2. 按 channel 顺序打开对应 App 的聊天页并把 message 预填到输入框（微信/QQ 通过 App 首页）
     *  3. ⚠️ 绝不自动点"发送"按钮，由用户人工确认后发送
     */
    suspend fun sendChatMessage(
        contactNameOrPhone: String,
        message: String,
        channel: ChatChannel
    ): Result<Unit>

    /**
     * 打车去某地：
     *  1. 优先滴滴/高德/百度地图 App（按已安装顺序）
     *  2. 用 deeplink 把目的地预填
     *  3. ⚠️ 绝不自动点"立即叫车"或支付，由用户人工选车型并确认
     */
    suspend fun callRide(
        destination: String,
        carTypePref: String? = null
    ): Result<Unit>
}
