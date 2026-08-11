package com.lingshu.feature.control.data

import com.lingshu.feature.control.domain.Command

data class QuickCommand(
    val id: String,
    val label: String,
    val command: Command
)

object QuickCommands {
    val list = listOf(
        QuickCommand(
            id = "open_wechat",
            label = "打开微信",
            command = Command.OpenApp(
                appName = "微信",
                packageName = "com.tencent.mm"
            )
        ),
        QuickCommand(
            id = "open_douyin",
            label = "打开抖音",
            command = Command.OpenApp(
                appName = "抖音",
                packageName = "com.ss.android.ugc.aweme"
            )
        ),
        QuickCommand(
            id = "open_settings",
            label = "打开设置",
            command = Command.OpenApp(
                appName = "设置",
                packageName = "com.android.settings"
            )
        ),
        QuickCommand(
            id = "screenshot",
            label = "截屏",
            command = Command.Screenshot
        ),
        QuickCommand(
            id = "brightness_up",
            label = "调高亮度(+20%)",
            command = Command.SystemControl(
                action = com.lingshu.feature.control.domain.SystemAction.BRIGHTNESS_UP
            )
        ),
        QuickCommand(
            id = "brightness_down",
            label = "调低亮度(-20%)",
            command = Command.SystemControl(
                action = com.lingshu.feature.control.domain.SystemAction.BRIGHTNESS_DOWN
            )
        ),
        QuickCommand(
            id = "volume_50",
            label = "音量调到50%",
            command = Command.SystemControl(
                action = com.lingshu.feature.control.domain.SystemAction.VOLUME_50
            )
        ),
        QuickCommand(
            id = "flashlight_on",
            label = "打开手电筒",
            command = Command.SystemControl(
                action = com.lingshu.feature.control.domain.SystemAction.FLASHLIGHT_ON
            )
        ),
        QuickCommand(
            id = "flashlight_off",
            label = "关闭手电筒",
            command = Command.SystemControl(
                action = com.lingshu.feature.control.domain.SystemAction.FLASHLIGHT_OFF
            )
        ),
        QuickCommand(
            id = "wifi_on",
            label = "打开WiFi",
            command = Command.SystemControl(
                action = com.lingshu.feature.control.domain.SystemAction.WIFI_ON
            )
        ),
        QuickCommand(
            id = "wifi_off",
            label = "关闭WiFi",
            command = Command.SystemControl(
                action = com.lingshu.feature.control.domain.SystemAction.WIFI_OFF
            )
        ),
        QuickCommand(
            id = "volume_mute",
            label = "静音模式",
            command = Command.SystemControl(
                action = com.lingshu.feature.control.domain.SystemAction.VOLUME_MUTE
            )
        )
    )

    fun findById(id: String): QuickCommand? = list.find { it.id == id }
}
