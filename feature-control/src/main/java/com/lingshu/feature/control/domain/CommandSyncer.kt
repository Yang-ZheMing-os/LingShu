package com.lingshu.feature.control.domain

import com.lingshu.core.common.error.ErrorCodes
import com.lingshu.core.common.error.Result
import com.lingshu.core.common.event.ICommandSyncer
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.feature.control.data.CommandParserImpl
import com.lingshu.feature.control.domain.scenes.SceneExecutionResult
import com.lingshu.feature.control.domain.scenes.SceneExecutor
import com.lingshu.feature.control.domain.scenes.SceneMatch
import com.lingshu.feature.control.domain.scenes.SceneResolver
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommandSyncer @Inject constructor(
    private val parser: ICommandParser,
    private val executor: CommandExecutor,
    private val systemControl: ISystemControl,
    private val sceneResolver: SceneResolver,
    private val sceneExecutor: SceneExecutor
) : ICommandSyncer {
    companion object {
        private const val TAG = "CommandSyncer"
    }

    override suspend fun sync(userInput: String): String? {
        val trimmed = userInput.trim()
        if (trimmed.isBlank()) {
            LingShuLog.v(TAG, "输入为空，跳过")
            return null
        }
        LingShuLog.i(TAG, "========== 开始同步执行指令 ==========")
        LingShuLog.d(TAG, "原始输入: ${trimmed.take(120)}")

        // ===== Step 1：优先尝试通用场景框架（覆盖三大 + 自定义） =====
        val sceneMatch = runCatching { sceneResolver.resolve(trimmed) }
            .onFailure { LingShuLog.w(TAG, "SceneResolver.resolve 异常，回落到单动作解析", it) }
            .getOrNull()
        when (sceneMatch) {
            is SceneMatch.Ok -> {
                LingShuLog.i(
                    TAG,
                    "✅ 场景框架命中：${sceneMatch.scene.sceneId} " +
                            "slots=${sceneMatch.filledSlots} steps=${sceneMatch.progressTexts.size}"
                )
                return when (val r = sceneExecutor.execute(sceneMatch)) {
                    is SceneExecutionResult.Success -> r.finalText
                    is SceneExecutionResult.PartialFailure ->
                        "执行到第${r.failedStepIndex + 1}步出了点问题：${r.message}"
                }
            }
            is SceneMatch.MissingSlot -> {
                LingShuLog.i(TAG, "⚠️ 场景 ${sceneMatch.scene.sceneId} 缺槽位 ${sceneMatch.slotName} → 反问用户")
                return buildMissingSlotReply(sceneMatch)
            }
            is SceneMatch.StepError -> {
                LingShuLog.w(TAG, "场景 ${sceneMatch.scene.sceneId} step=${sceneMatch.stepId} 翻译失败：${sceneMatch.message}")
                return null  // 让 AI 回答兜底
            }
            null -> Unit  // 未命中场景 → 继续单动作
        }

        // ===== Step 2：回落到单动作解析（系统控制 / 单步 App / UI 自动化） =====
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

    override fun topSimilarSuggestions(userInput: String, limit: Int): List<String> {
        val sims = runCatching { parser.topSimilarExamples(userInput, limit.coerceAtLeast(1)) }
            .onFailure { LingShuLog.w(TAG, "topSimilarSuggestions 异常", it) }
            .getOrDefault(emptyList())
            .take(limit.coerceAtLeast(1))
        LingShuLog.d(
            TAG,
            "topSimilarSuggestions(input=\"${userInput.take(40)}\", limit=$limit) → size=${sims.size} sample=${sims.firstOrNull()}"
        )
        return sims
    }

    override fun isUnknown(userInput: String): Boolean {
        val trimmed = userInput.trim()
        if (trimmed.isBlank()) return true
        val scene = runCatching { runBlocking { sceneResolver.resolve(trimmed) } }
            .onFailure { LingShuLog.w(TAG, "isUnknown 场景解析异常，按 Unknown 处理", it) }
            .getOrNull()
        if (scene != null) {
            LingShuLog.d(
                TAG,
                "isUnknown(\"${trimmed.take(40)}\")=false，因为命中场景 ${scene.javaClass.simpleName}"
            )
            return false
        }
        val cmd = parser.parse(trimmed)
        val unknown = cmd is Command.Unknown
        LingShuLog.d(
            TAG,
            "isUnknown(\"${trimmed.take(40)}\")=$unknown，单动作解析结果=${cmd.javaClass.simpleName}"
        )
        return unknown
    }

    // ===== 内部 =====

    private fun buildMissingSlotReply(m: SceneMatch.MissingSlot): String {
        val prefix = "好的。"
        val ask = m.askPrompt.ifBlank { "请告诉我 ${m.slotName}：" }
        // 把「已抽到的槽」翻译成自然语言说给用户听（不暴露 {k=v} 这种实现细节）
        val knownClauses: List<String> = buildList {
            for (slot in m.scene.slots) {
                val v = m.partialSlots[slot.name]?.toString()?.takeIf { it.isNotBlank() } ?: continue
                if (slot.name == m.slotName) continue
                // 显示层过滤：对「默认值/泛称/无信息量」的槽不展示，避免用户看到「车型你偏好『车』」这种废话
                val skip = when (slot.name) {
                    "carType" -> v.trim() in listOf("车", "汽车", "打车", "网约车", "默认", "默认车型", "普通")
                    "minute" -> v.trim().toIntOrNull() == 0
                    "channel" -> v.trim().equals("WECHAT", ignoreCase = true) && m.scene.sceneId == "builtin_send_chat"
                                && m.partialSlots.size == 1 // 只有 channel 一个已知槽时不单独展示，后面会连 recipient 一起说
                    else -> false
                }
                if (skip) continue
                add(
                    when (slot.name) {
                        "carType" -> "车型你偏好「${v}」"
                        "channel" -> "走「${v}」"
                        "contact" -> "收件人「${v}」"
                        "message" -> "内容「${v}」"
                        "destination" -> "目的地「${v}」"
                        "food" -> "想吃「${v}」"
                        "address" -> "送到「${v}」"
                        "restaurant" -> "去「${v}」下单"
                        "hour" -> "时间「${v}:${(m.partialSlots["minute"]?.toString()?.padStart(2,'0') ?: "00")}」"
                        "target" -> "「${v}」"
                        "query" -> "关键词「${v}」"
                        "appName" -> "App「${v}」"
                        "label" -> "标签「${v}」"
                        else -> "「${slot.name}」=「${v}」"
                    }
                )
            }
        }
        LingShuLog.d(
            TAG,
            "MissingSlot scene=${m.scene.sceneId} 缺槽=${m.slotName} 已知=${m.partialSlots.map { (k,v) -> "$k=$v" }.joinToString("、")}"
        )
        val known = knownClauses.takeIf { it.isNotEmpty() }
            ?.joinToString("，", prefix = "（", postfix = "）")
            ?: ""
        return "$prefix$known $ask".trim()
    }

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
        is Command.WebSearch -> "正在搜索：${command.query}"
        Command.PlayMusic -> "正在播放音乐"
        is Command.SetAlarm -> if (command.hour != null) "闹钟已设置为 ${command.hour}:${command.minute.toString().padStart(2, '0')}" else "已打开闹钟"
        Command.OpenCamera -> "相机已打开"
        is Command.MakeCall -> "正在拨打电话给${command.phoneNumberOrContact}"
        is Command.SendSms -> "正在给${command.phoneNumberOrContact}发短信"

        // ---------- 三大复合场景：AI canonical reply 都会明确说"我不自动确认，需要你最后点一下" ----------
        is Command.OrderTakeout -> {
            val sb = StringBuilder("正在为你打开外卖App")
            listOfNotNull(command.restaurant, command.foodKeyword).let {
                if (it.isNotEmpty()) sb.append("，已为你搜索「${it.joinToString(" ")}」")
            }
            if (!command.addressHint.isNullOrBlank()) sb.append("，收货地址备注：${command.addressHint}")
            sb.append("。⚠️ 为了安全，不会自动下单/支付，请你选择餐品后人工确认并完成支付哦")
            sb.toString()
        }
        is Command.SendChatMessage -> {
            val channelText = when (command.channel) {
                ChatChannel.WECHAT -> "微信"
                ChatChannel.QQ -> "QQ"
                ChatChannel.SMS -> "短信"
                ChatChannel.UNKNOWN -> "微信/QQ/短信（优先已安装的）"
            }
            "正在通过$channelText 联系 ${command.contactNameOrPhone}，消息内容已复制好。" +
                "⚠️ 为了确保消息内容符合你的意愿，不会自动点击发送按钮，请你确认内容后手动发送哦"
        }
        is Command.CallRide -> {
            val sb = StringBuilder("正在为你打开打车App，目的地已预填为「${command.destination}」")
            command.carTypePref?.let { sb.append("，车型偏好：$it") }
            sb.append("。⚠️ 为了安全，不会自动呼叫车辆或支付，请你选好车型后人工确认叫车哦")
            sb.toString()
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
