package com.lingshu.feature.control.domain

sealed class Command {
    data class SystemControl(val action: SystemAction) : Command()
    data class OpenApp(val appName: String, val packageName: String) : Command()
    data class CloseApp(val appName: String) : Command()
    data object Screenshot : Command()
    data class Unknown(val input: String) : Command()
}
