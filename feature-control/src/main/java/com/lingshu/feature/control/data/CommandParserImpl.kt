package com.lingshu.feature.control.data

import com.lingshu.core.common.log.LingShuLog
import com.lingshu.feature.control.domain.Command
import com.lingshu.feature.control.domain.ICommandParser
import com.lingshu.feature.control.domain.SystemAction
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommandParserImpl @Inject constructor() : ICommandParser {

    override fun parse(userInput: String): Command {
        val input = userInput.trim().lowercase()
        LingShuLog.d("CommandParser", "解析指令: $userInput")

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

    private fun parseAppCommand(input: String): Command? {
        val openPatterns = listOf("打开", "启动", "开启", "运行", "open", "launch", "start")
        val closePatterns = listOf("关闭", "退出", "关掉", "close", "exit", "quit")

        for (pattern in openPatterns) {
            if (input.startsWith(pattern) || input.contains(pattern)) {
                val appName = input.replace(pattern, "").trim()
                if (appName.isNotEmpty()) {
                    val packageName = getPackageNameByAppName(appName)
                    return Command.OpenApp(appName = appName, packageName = packageName)
                }
            }
        }

        for (pattern in closePatterns) {
            if (input.startsWith(pattern) || input.contains(pattern)) {
                val appName = input.replace(pattern, "").trim()
                if (appName.isNotEmpty()) {
                    return Command.CloseApp(appName = appName)
                }
            }
        }

        return null
    }

    private fun getPackageNameByAppName(appName: String): String {
        return when (appName.lowercase()) {
            "微信" -> "com.tencent.mm"
            "抖音" -> "com.ss.android.ugc.aweme"
            "设置" -> "com.android.settings"
            "相机" -> "com.android.camera"
            "相册" -> "com.android.gallery"
            "音乐" -> "com.android.music"
            "浏览器" -> "com.android.browser"
            "日历" -> "com.android.calendar"
            "时钟" -> "com.android.deskclock"
            "计算器" -> "com.android.calculator2"
            else -> ""
        }
    }
}
