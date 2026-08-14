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
