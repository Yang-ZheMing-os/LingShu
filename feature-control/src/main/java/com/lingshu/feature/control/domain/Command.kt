package com.lingshu.feature.control.domain

sealed class Command {
    data class SystemControl(val action: SystemAction) : Command()
    data class OpenApp(val appName: String, val packageName: String) : Command()
    data class CloseApp(val appName: String) : Command()
    data object Screenshot : Command()

    /** 导航到指定目的地（地点名/地址） */
    data class Navigate(val destination: String) : Command()

    /** 打开外卖应用（美团外卖/饿了么） */
    data object OpenTakeout : Command()

    /**
     * 点外卖（复合场景·安全合规版）
     *
     *  1. 打开美团外卖 / 饿了么 App（优先用户安装的那个）
     *  2. 若提供 restaurant/foodKeyword，则通过 deeplink 或搜索接口预填关键词
     *  3. 地址尽量通过用户默认地址（不做自动下单/支付）
     *
     * 【安全边界】App 只负责"打开并预填"，最后选规格、下单、支付由用户人工确认。
     *  AI canonical reply 会明确告诉用户这一点。
     */
    data class OrderTakeout(
        /** 想吃的菜/品类，例 "酸菜鱼"、"奶茶"、"麦当劳" */
        val foodKeyword: String? = null,
        /** 商家名/品牌名，例 "肯德基"、"海底捞外送" */
        val restaurant: String? = null,
        /** 预设收货地址备注（仅文字，不做地址级定位） */
        val addressHint: String? = null
    ) : Command()

    /** 网页搜索："搜索XX" / "查一下XX" / "百度XX" */
    data class WebSearch(val query: String) : Command()

    /** 播放音乐："播放音乐" / "听歌" / "放首歌" */
    data object PlayMusic : Command()

    /** 设置闹钟："设闹钟" / "定闹钟XX点" */
    data class SetAlarm(val hour: Int? = null, val minute: Int = 0, val label: String? = null) : Command()

    /** 打开相机拍照 */
    data object OpenCamera : Command()

    /** 拨打电话："打电话给XX" */
    data class MakeCall(val phoneNumberOrContact: String) : Command()

    /** 发送短信 */
    data class SendSms(val phoneNumberOrContact: String, val message: String = "") : Command()

    /** 发送聊天消息（微信 / QQ 等即时通讯渠道）
     *
     * 【安全边界】只负责打开目标 App 的聊天页并预填 message 文本，
     *  不自动点击"发送"按钮，由用户人工确认。
     *
     * @param contactNameOrPhone 联系人昵称/姓名/手机号（例 "我妈"、"张三"、"10086"）
     * @param message 要发送的文本内容
     * @param channel 优先使用哪个 IM 渠道（UNKNOWN 时依次尝试 微信 → QQ → 短信兜底）
     */
    data class SendChatMessage(
        val contactNameOrPhone: String,
        val message: String,
        val channel: ChatChannel = ChatChannel.UNKNOWN
    ) : Command()

    /**
     * 打车到指定目的地（复合场景·安全合规版）
     *
     *  1. 打开高德/滴滴/百度地图 App（优先已安装的）
     *  2. 预填目的地（起点默认当前 GPS 位置，由地图 App 自身获取）
     *  3. 【安全边界】最后选车型、确认叫车、支付由用户人工点击完成。
     *
     * @param destination 目的地地址/地标名，例 "首都机场T3"、"公司"、"西湖景区"
     * @param carTypePref 车型偏好（仅文字描述，不一定生效），例 "快车"、"拼车"
     */
    data class CallRide(
        val destination: String,
        val carTypePref: String? = null
    ) : Command()

    /**
     * App 内部自动化操作：先打开指定 App，再通过无障碍服务执行界面操作。
     *
     * @param appName  目标 App 名称（如"微信"）
     * @param action   操作类型（如"send_message"）
     * @param params   操作参数（如 {"contact":"XXX","message":"你好"}）
     */
    data class AppAction(
        val appName: String,
        val action: String,
        val params: Map<String, String>
    ) : Command()

    // ==================== UI 自动化指令（依赖无障碍服务） ====================

    /** 点击指定坐标 */
    data class UiTap(val x: Int, val y: Int) : Command()

    /** 点击屏幕上文本/内容描述匹配的控件（控件名驱动，不依赖坐标） */
    data class UiTapText(val text: String) : Command()

    /** 从 (x1,y1) 滑动到 (x2,y2)，duration 毫秒 */
    data class UiSwipe(
        val x1: Int,
        val y1: Int,
        val x2: Int,
        val y2: Int,
        val duration: Int
    ) : Command()

    /** 按方向滚动一屏（基于屏幕尺寸自动计算滑动起止点） */
    data class UiScroll(val direction: ScrollDirection) : Command()

    /** 向当前焦点输入框输入文本 */
    data class UiInputText(val text: String) : Command()

    /** 按返回键 */
    data object UiPressBack : Command()

    /** 按 Home 键 */
    data object UiPressHome : Command()

    /** 在 (x,y) 长按 durationMs 毫秒 */
    data class UiLongPress(val x: Float, val y: Float, val durationMs: Long) : Command()

    data class Unknown(val input: String) : Command()
}

/** 滚动方向 */
enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

/** IM 消息发送渠道（SendChatMessage.channel） */
enum class ChatChannel {
    /** 用户没指定 → 依次尝试 微信 → QQ → 短信兜底 */
    UNKNOWN,
    /** 微信（com.tencent.mm） */
    WECHAT,
    /** QQ（com.tencent.mobileqq） */
    QQ,
    /** 强制走短信（兜底） */
    SMS
}
