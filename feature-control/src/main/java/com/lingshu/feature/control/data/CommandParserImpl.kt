package com.lingshu.feature.control.data

import com.lingshu.core.common.log.LingShuLog
import com.lingshu.feature.control.domain.Command
import com.lingshu.feature.control.domain.ICommandParser
import com.lingshu.feature.control.domain.ISystemControl
import com.lingshu.feature.control.domain.ScrollDirection
import com.lingshu.feature.control.domain.SystemAction
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommandParserImpl @Inject constructor(
    private val systemControl: ISystemControl
) : ICommandParser {

    override fun parse(userInput: String): Command {
        val input = userInput.trim().lowercase()
        LingShuLog.d("CommandParser", "解析指令: $userInput")

        // 预解析导航 / App 内操作 / UI 自动化（需提取参数），命中则直接返回
        val navigate = parseNavigate(input)
        val appAction = parseAppAction(input)
        val uiScroll = parseUiScroll(input)
        val uiTapText = parseUiTapText(input)

        return when {
            isScreenshotCommand(input) -> Command.Screenshot

            isWifiOnCommand(input) -> Command.SystemControl(SystemAction.WIFI_ON)
            isWifiOffCommand(input) -> Command.SystemControl(SystemAction.WIFI_OFF)

            isBluetoothOnCommand(input) -> Command.SystemControl(SystemAction.BLUETOOTH_ON)
            isBluetoothOffCommand(input) -> Command.SystemControl(SystemAction.BLUETOOTH_OFF)

            isFlashlightOnCommand(input) -> Command.SystemControl(SystemAction.FLASHLIGHT_ON)
            isFlashlightOffCommand(input) -> Command.SystemControl(SystemAction.FLASHLIGHT_OFF)

            isBrightnessUpCommand(input) -> Command.SystemControl(SystemAction.BRIGHTNESS_UP)
            isBrightnessDownCommand(input) -> Command.SystemControl(SystemAction.BRIGHTNESS_DOWN)

            isVolume50Command(input) -> Command.SystemControl(SystemAction.VOLUME_50)
            isVolumeMuteCommand(input) -> Command.SystemControl(SystemAction.VOLUME_MUTE)
            isVolumeUpCommand(input) -> Command.SystemControl(SystemAction.VOLUME_UP)
            isVolumeDownCommand(input) -> Command.SystemControl(SystemAction.VOLUME_DOWN)

            isAutoRotateOnCommand(input) -> Command.SystemControl(SystemAction.AUTO_ROTATE_ON)
            isAutoRotateOffCommand(input) -> Command.SystemControl(SystemAction.AUTO_ROTATE_OFF)

            // ===== UI 自动化（无障碍）：无参数指令优先级高，不会被"打开XX"误伤 =====
            isUiPressBackCommand(input) -> Command.UiPressBack
            isUiPressHomeCommand(input) -> Command.UiPressHome
            uiScroll != null -> uiScroll

            navigate != null -> navigate
            isTakeoutCommand(input) -> Command.OpenTakeout
            appAction != null -> appAction

            // "点击XX"放在外卖/AppAction 之后，避免"点外卖"被误解析为文本点击
            uiTapText != null -> uiTapText

            else -> parseAppCommand(input) ?: Command.Unknown(userInput)
        }
    }

    private fun isScreenshotCommand(input: String): Boolean {
        return input.contains("截屏") || input.contains("截图") ||
               input.contains("screenshot")
    }

    private fun isWifiOnCommand(input: String): Boolean {
        return (input.contains("打开") || input.contains("开启")) &&
               (input.contains("wifi") || input.contains("wi-fi") || input.contains("无线网") ||
                input.contains("wifi"))
    }

    private fun isWifiOffCommand(input: String): Boolean {
        return (input.contains("关闭") || input.contains("关掉")) &&
               (input.contains("wifi") || input.contains("wi-fi") || input.contains("无线网") ||
                input.contains("wifi"))
    }

    private fun isBluetoothOnCommand(input: String): Boolean {
        return (input.contains("打开") || input.contains("开启")) &&
               input.contains("蓝牙")
    }

    private fun isBluetoothOffCommand(input: String): Boolean {
        return (input.contains("关闭") || input.contains("关掉")) &&
               input.contains("蓝牙")
    }

    private fun isFlashlightOnCommand(input: String): Boolean {
        return (input.contains("打开") || input.contains("开启")) &&
               (input.contains("手电筒") || input.contains("闪光灯") || input.contains("torch") ||
                input.contains("flashlight"))
    }

    private fun isFlashlightOffCommand(input: String): Boolean {
        return (input.contains("关闭") || input.contains("关掉")) &&
               (input.contains("手电筒") || input.contains("闪光灯") || input.contains("torch") ||
                input.contains("flashlight"))
    }

    private fun isBrightnessUpCommand(input: String): Boolean {
        return (input.contains("调高") || input.contains("增加") || input.contains("+") ||
                input.contains("提高")) &&
               (input.contains("亮度") || input.contains("brightness"))
    }

    private fun isBrightnessDownCommand(input: String): Boolean {
        return (input.contains("调低") || input.contains("降低") || input.contains("-") ||
                input.contains("减小")) &&
               (input.contains("亮度") || input.contains("brightness"))
    }

    private fun isVolume50Command(input: String): Boolean {
        return (input.contains("音量") || input.contains("volume")) &&
               (input.contains("50%") || input.contains("50％") || input.contains("一半") ||
                input.contains("50"))
    }

    private fun isVolumeMuteCommand(input: String): Boolean {
        return input.contains("静音") || input.contains("mute") ||
               input.contains("无声") || input.contains("音量为0")
    }

    private fun isVolumeUpCommand(input: String): Boolean {
        return (input.contains("调高") || input.contains("增加") || input.contains("+") ||
                input.contains("提高") || input.contains("调大")) &&
               (input.contains("音量") || input.contains("声音") || input.contains("volume"))
    }

    private fun isVolumeDownCommand(input: String): Boolean {
        return (input.contains("调低") || input.contains("降低") || input.contains("-") ||
                input.contains("减小") || input.contains("调小")) &&
               (input.contains("音量") || input.contains("声音") || input.contains("volume"))
    }

    private fun isAutoRotateOnCommand(input: String): Boolean {
        return (input.contains("打开") || input.contains("开启")) &&
               (input.contains("自动旋转") || input.contains("旋转"))
    }

    private fun isAutoRotateOffCommand(input: String): Boolean {
        return (input.contains("关闭") || input.contains("关掉")) &&
               (input.contains("自动旋转") || input.contains("旋转"))
    }

    // ===== UI 自动化指令解析（依赖无障碍服务） =====

    /** "返回" / "后退" / "返回上一页" / "back" */
    private fun isUiPressBackCommand(input: String): Boolean {
        return input.contains("返回") || input.contains("后退") || input == "back"
    }

    /** "回到桌面" / "回桌面" / "回主屏" / "回主页" / "回主屏幕" / "home" */
    private fun isUiPressHomeCommand(input: String): Boolean {
        return input.contains("回到桌面") || input.contains("回桌面") ||
               input.contains("回主屏") || input.contains("回主页") ||
               input.contains("回主屏幕") || input == "home"
    }

    /**
     * 解析滚动方向："上滑"/"向上滑"/"往上滑"/"向上滚动"/"上翻" 等。
     * 词表覆盖"向X滑/X滑/向X滚动/X滚动/X翻"五种口语组合。返回 null 表示未命中。
     */
    private fun parseUiScroll(input: String): Command.UiScroll? {
        val direction = when {
            input.contains("上滑") || input.contains("上滚动") || input.contains("上翻") -> ScrollDirection.UP
            input.contains("下滑") || input.contains("下滚动") || input.contains("下翻") -> ScrollDirection.DOWN
            input.contains("左滑") || input.contains("左滚动") -> ScrollDirection.LEFT
            input.contains("右滑") || input.contains("右滚动") -> ScrollDirection.RIGHT
            else -> return null
        }
        // 仅当输入确实是"滑动/滚动/翻页"语义时才命中，避免"上滑屏幕截图"这类复合句误伤（暂按 contains 处理，保持简单）
        return Command.UiScroll(direction)
    }

    /**
     * 解析文本点击："点击XX" / "点一下XX" / "点按XX" / "按下XX"。
     * 只用明确的动词词头，单字"点"不参与（"点外卖"等由 takeout/appAction 分支优先处理）。
     */
    private fun parseUiTapText(input: String): Command.UiTapText? {
        val tailRegex = Regex("(一下|吧|了|可以吗|好吗|呗|啦|啊|哦|呀)\$")
        for (verb in listOf("点击", "点一下", "点按", "按下", "按一下")) {
            if (input.startsWith(verb)) {
                val target = input.removePrefix(verb).replace(tailRegex, "").trim()
                if (target.isNotEmpty()) {
                    return Command.UiTapText(target)
                }
            }
        }
        return null
    }

    /**
     * 解析导航指令："导航到XXX" / "导航至XXX" / "导航去XXX" / "前往XXX" / "去XXX" / "导航XXX"。
     * 前缀按长度从长到短匹配，避免"导航"吃掉"导航到"。返回 null 表示未命中。
     */
    private fun parseNavigate(input: String): Command.Navigate? {
        val prefixes = listOf("导航到", "导航至", "导航去", "前往", "导航", "去")
        for (prefix in prefixes) {
            if (input.startsWith(prefix)) {
                val dest = input.removePrefix(prefix).trim()
                if (dest.isNotEmpty()) {
                    return Command.Navigate(dest)
                }
            }
        }
        return null
    }

    /** 解析外卖指令："点外卖" / "叫外卖" / "订外卖" / "打开外卖" / "开外卖" */
    private fun isTakeoutCommand(input: String): Boolean {
        return input.contains("外卖") && (
            input.contains("点") || input.contains("叫") || input.contains("订") ||
                input.contains("打开") || input.contains("开启") || input.contains("开"))
    }

    /**
     * 解析 App 内操作指令，目前支持发送消息：
     * "在微信里发消息给XXX" / "用微信发消息给XXX" / "在微信给XXX发消息"。
     * 返回 null 表示未命中。
     */
    private fun parseAppAction(input: String): Command.AppAction? {
        val patterns = listOf(
            Regex("在(.+?)(?:里|中|上)发消息给(.+)"),
            Regex("用(.+?)发消息给(.+)"),
            Regex("在(.+?)给(.+?)发消息")
        )
        for (pattern in patterns) {
            val m = pattern.find(input) ?: continue
            val appName = m.groupValues[1].trim()
            val contact = m.groupValues[2].trim()
            if (appName.isNotEmpty() && contact.isNotEmpty()) {
                return Command.AppAction(
                    appName = appName,
                    action = "send_message",
                    params = mapOf("contact" to contact)
                )
            }
        }
        return null
    }

    private fun parseAppCommand(input: String): Command? {
        // 去掉前导口语修饰词（帮我/请/我想/给我 等），让"帮我打开设置"也能识别
        val prefixRegex = Regex("^(帮我|麻烦你|麻烦|请你|请|我想|我要|给我|能不能|可以|可以帮我|我要你|能不能帮我|你帮我|你给我)")
        val cleaned = input.replace(prefixRegex, "").trim()

        val openActions = listOf("打开", "启动", "开启", "运行")
        val closeActions = listOf("关闭", "退出", "关掉")

        // 匹配"动作+应用名"，取动作词后的内容作为应用名，并清理尾部语气词
        val tailRegex = Regex("(一下|吧|了|可以吗|好吗|呗|啦|啊|哦|呀)\$")
        for (action in openActions) {
            if (cleaned.contains(action)) {
                val after = cleaned.substringAfter(action).trim()
                val appName = after.replace(tailRegex, "").trim()
                if (appName.isNotEmpty()) {
                    val packageName = getPackageNameByAppName(appName)
                    return Command.OpenApp(appName = appName, packageName = packageName)
                }
            }
        }

        for (action in closeActions) {
            if (cleaned.contains(action)) {
                val after = cleaned.substringAfter(action).trim()
                val appName = after.replace(tailRegex, "").trim()
                if (appName.isNotEmpty()) {
                    return Command.CloseApp(appName = appName)
                }
            }
        }

        // 英文动作
        val enOpen = listOf("open", "launch", "start")
        val enClose = listOf("close", "exit", "quit")
        for (action in enOpen) {
            if (cleaned.startsWith(action) || cleaned.contains(" " + action + " ")) {
                val appName = cleaned.substringAfter(action).trim()
                    .replace(Regex("(please|plz)"), "").trim()
                if (appName.isNotEmpty()) {
                    val packageName = getPackageNameByAppName(appName)
                    return Command.OpenApp(appName = appName, packageName = packageName)
                }
            }
        }
        for (action in enClose) {
            if (cleaned.startsWith(action) || cleaned.contains(" " + action + " ")) {
                val appName = cleaned.substringAfter(action).trim()
                if (appName.isNotEmpty()) {
                    return Command.CloseApp(appName = appName)
                }
            }
        }

        return null
    }


    /** 包名解析委托给 [ISystemControl]，统一维护映射表 */
    private fun getPackageNameByAppName(appName: String): String =
        systemControl.getPackageNameByAppName(appName)
}
