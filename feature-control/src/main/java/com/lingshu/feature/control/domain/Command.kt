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

    data class Unknown(val input: String) : Command()
}
