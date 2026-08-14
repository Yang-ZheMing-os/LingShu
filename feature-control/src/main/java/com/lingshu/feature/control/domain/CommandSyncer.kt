package com.lingshu.feature.control.domain

import com.lingshu.core.common.error.ErrorCodes
import com.lingshu.core.common.error.Result
import com.lingshu.core.common.event.ICommandSyncer
import com.lingshu.core.common.log.LingShuLog
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommandSyncer @Inject constructor(
    private val parser: ICommandParser,
    private val executor: CommandExecutor,
    private val systemControl: ISystemControl
) : ICommandSyncer {
    companion object {
        private const val TAG = "CommandSyncer"
    }

    /**
     * 对用户输入做一次同步识别+执行。
     *
     * @param userInput 用户的原文（例："打开微信""调高亮度""导航到天安门"）
     * @return 执行成功时返回规范短句（例："微信应用已打开"），未识别/失败返回 null
     */
    override suspend fun sync(userInput: String): String? {
        val trimmed = userInput.trim()
        if (trimmed.isBlank()) {
            LingShuLog.v(TAG, "输入为空，跳过")
            return null
        }
        LingShuLog.i(TAG, "========== 开始同步执行指令 ==========")
        LingShuLog.d(TAG, "原始输入: ${trimmed.take(120)}")

        val command = parser.parse(trimmed)
        LingShuLog.d(TAG, "parser.parse 结果: $command")

        if (command is Command.Unknown) {
            LingShuLog.i(TAG, "未命中控制指令（Command.Unknown），结束同步执行")
            return null
        }

        val resolvedCommand = resolveOpenAppPkgIfNeeded(command) ?: run {
            LingShuLog.w(TAG, "OpenApp 包名解析最终失败 → 返回null，不覆盖 AI 回复")
            return null
        }
        LingShuLog.d(TAG, "解析包名后最终指令: $resolvedCommand")

        val result: Result<Unit> = try {
            LingShuLog.d(TAG, "调用 CommandExecutor.execute ...")
            executor.execute(resolvedCommand).also {
                LingShuLog.i(TAG, "CommandExecutor.execute 返回: $it")
            }
        } catch (e: Exception) {
            LingShuLog.e(TAG, "执行抛出异常: $resolvedCommand", e)
            Result.error(code = ErrorCodes.UNKNOWN_ERROR, message = e.message ?: "执行异常", cause = e)
        }

        if (result !is Result.Success) {
            val errCode = (result as? Result.Error)?.code
            val errMsg = (result as? Result.Error)?.message ?: ""
            LingShuLog.w(TAG, "执行结果非成功: code=$errCode msg=$errMsg")
            // 应用未安装时给用户明确提示（而不是沿用 LLM 的"模型不可用"之类的回复）
            if (resolvedCommand is Command.OpenApp && errMsg.contains("未安装")) {
                val name = resolvedCommand.appName.takeIf { it.isNotBlank() } ?: "该应用"
                return "未找到「$name」，可能未安装或名称不匹配"
            }
            // UI 自动化指令依赖无障碍服务，未开启时给出开通引导
            if (errCode == ErrorCodes.ACCESSIBILITY_DISABLED) {
                return "此操作需要无障碍服务，请到系统设置中开启「灵枢」的无障碍权限"
            }
            return null
        }

        val reply = buildCanonicalReply(resolvedCommand)
        LingShuLog.i(TAG, "✅ 全部成功 → 覆盖 AI 回复: \"$reply\"（指令: $resolvedCommand）")
        return reply
    }

    // ===== 内部 =====

    private fun resolveOpenAppPkgIfNeeded(command: Command): Command? = when (command) {
        is Command.OpenApp -> {
            LingShuLog.d(TAG, "resolveOpenAppPkgIfNeeded: 原 pkg=[${command.packageName}] 原 appName=[${command.appName}]")
            val pkg = resolvePackageName(command.appName, command.packageName)
            if (pkg.isBlank()) null else {
                LingShuLog.d(TAG, "resolveOpenAppPkgIfNeeded: 最终解析到 pkg=$pkg")
                command.copy(packageName = pkg)
            }
        }
        is Command.CloseApp -> command
        else -> command
    }

    /**
     * 两级包名解析兜底：
     * 1) 原包名非空直接用
     * 2) 走 ISystemControl（APP_PACKAGE_MAP 静态 + PackageManager 本地 label 模糊匹配）
     */
    private fun resolvePackageName(appName: String, rawPkg: String): String {
        if (rawPkg.isNotBlank()) {
            LingShuLog.d(TAG, "resolvePackageName: 直接使用传入包名: $rawPkg")
            return rawPkg.trim()
        }
        if (appName.isBlank()) return ""
        val resolved = systemControl.getPackageNameByAppName(appName)
        if (resolved.isNotBlank()) {
            LingShuLog.d(TAG, "resolvePackageName: systemControl 解析到: $appName -> $resolved")
            return resolved
        }
        LingShuLog.w(TAG, "resolvePackageName: systemControl 解析不到包名: appName=$appName")
        return ""
    }

    /** 根据执行结果生成规范短句（返回给 UI 显示/覆盖 AI 回复） */
    private fun buildCanonicalReply(command: Command): String = when (command) {
        is Command.OpenApp -> {
            val name = command.appName.takeIf { it.isNotBlank() }
                ?: executor.appDisplayName(command.packageName)
                ?: "应用"
            "${name}应用已打开"
        }
        is Command.CloseApp -> {
            val name = command.appName.takeIf { it.isNotBlank() } ?: "应用"
            "${name}已关闭"
        }
        is Command.Navigate -> "已为您打开导航，目的地：${command.destination}"
        Command.OpenTakeout -> "外卖应用已打开"
        Command.Screenshot -> "已为您截屏"
        is Command.UiTap -> "已点击屏幕 (${command.x}, ${command.y})"
        is Command.UiTapText -> "已点击「${command.text}」"
        is Command.UiSwipe -> "已滑动屏幕：(${command.x1}, ${command.y1}) → (${command.x2}, ${command.y2})"
        is Command.UiScroll -> "已${directionText(command.direction)}滚动一屏"
        is Command.UiInputText -> "已输入文字：${command.text}"
        Command.UiPressBack -> "已按返回键"
        Command.UiPressHome -> "已按 Home 键"
        is Command.UiLongPress -> "已长按屏幕 (${command.x}, ${command.y})"
        is Command.SystemControl -> when (command.action) {
            SystemAction.WIFI_ON -> "Wi-Fi 已开启"
            SystemAction.WIFI_OFF -> "Wi-Fi 已关闭"
            SystemAction.BLUETOOTH_ON -> "蓝牙已开启"
            SystemAction.BLUETOOTH_OFF -> "蓝牙已关闭"
            SystemAction.FLASHLIGHT_ON -> "手电筒已开启"
            SystemAction.FLASHLIGHT_OFF -> "手电筒已关闭"
            SystemAction.VOLUME_UP -> "音量已调高"
            SystemAction.VOLUME_DOWN -> "音量已调低"
            SystemAction.VOLUME_MUTE -> "音量已静音"
            SystemAction.VOLUME_50 -> "音量已调整到 50%"
            SystemAction.BRIGHTNESS_UP -> "亮度已调高"
            SystemAction.BRIGHTNESS_DOWN -> "亮度已调低"
            SystemAction.AUTO_ROTATE_ON -> "自动旋转已开启"
            SystemAction.AUTO_ROTATE_OFF -> "自动旋转已关闭"
        }
        is Command.AppAction, is Command.Unknown -> ""
    }

    private fun directionText(d: ScrollDirection): String = when (d) {
        ScrollDirection.UP -> "向上"
        ScrollDirection.DOWN -> "向下"
        ScrollDirection.LEFT -> "向左"
        ScrollDirection.RIGHT -> "向右"
    }
}
