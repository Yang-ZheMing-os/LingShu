package com.lingshu.feature.control.domain

import com.lingshu.core.common.event.StartableBridge
import com.lingshu.core.common.event.AppEvent
import com.lingshu.core.common.event.IAppEventBus
import com.lingshu.core.common.event.on
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.core.common.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI \u56de\u590d -> \u63a7\u5236\u6267\u884c Bridge\u3002
 *
 * \u8ba2\u9605 [AppEvent.AiReplyFinished]\uff0c\u4ece AI \u56de\u590d\u4e2d\u89e3\u6790\u5de5\u5177\u8c03\u7528\u6807\u8bb0\u5e76\u6267\u884c\u3002
 */
@Singleton
class AiReplyToControlBridge @Inject constructor(
    private val bus: IAppEventBus,
    private val commandParser: ICommandParser,
    private val commandExecutor: CommandExecutor,
    private val systemControl: ISystemControl,
    @IoDispatcher private val handler: CoroutineDispatcher
) : StartableBridge {
    companion object {
        private const val TAG = "AiReplyToControlBridge"
        private const val TOOL_CALL_OPEN = "[TOOL_CALL]"
        private const val TOOL_CALL_CLOSE = "[/TOOL_CALL]"
        private val TOOL_REGEX = Regex("""\[TOOL_CALL]\s*(\{.*?\}|\[\s*\{.*?\}\s*])\s*\[/TOOL_CALL]""", RegexOption.DOT_MATCHES_ALL)

        /**
         * 从文本中提取所有 [TOOL_CALL]...[/TOOL_CALL] 标记的 JSON 内容。
         * 同时兼容两种格式：
         *   - 单对象：[TOOL_CALL] {"action":"open_app","args":{...}} [/TOOL_CALL]
         *   - 数组：  [TOOL_CALL] [{"action":"open_app","args":{...}}] [/TOOL_CALL]
         */
        internal fun parseToolCalls(text: String): List<JSONObject> {
            val results = mutableListOf<JSONObject>()
            for (match in TOOL_REGEX.findAll(text)) {
                val raw = match.groupValues[1].trim()
                try {
                    if (raw.startsWith('[')) {
                        // 数组格式：逐个展开成 JSONObject
                        val arr = org.json.JSONArray(raw)
                        for (i in 0 until arr.length()) {
                            runCatching { arr.getJSONObject(i) }
                                .onSuccess { results.add(it) }
                                .onFailure { e ->
                                    LingShuLog.w(TAG, "工具调用数组第 $i 个不是合法 JSONObject: $raw", e)
                                }
                        }
                    } else {
                        results.add(JSONObject(raw))
                    }
                } catch (e: Exception) {
                    LingShuLog.w(TAG, "解析工具调用 JSON 失败: $raw", e)
                }
            }
            return results
        }

        /** \u628a LLM \u8f93\u51fa\u7684 action \u540d\u6620\u5c04\u5230 [Command] */
        internal fun mapToolCallToCommand(action: String, args: JSONObject): Command? {
            return when (action) {
                "set_wifi" -> Command.SystemControl(
                    if (args.optBoolean("on", true)) SystemAction.WIFI_ON else SystemAction.WIFI_OFF
                )
                "set_bluetooth" -> Command.SystemControl(
                    if (args.optBoolean("on", true)) SystemAction.BLUETOOTH_ON else SystemAction.BLUETOOTH_OFF
                )
                "set_flashlight" -> Command.SystemControl(
                    if (args.optBoolean("on", true)) SystemAction.FLASHLIGHT_ON else SystemAction.FLASHLIGHT_OFF
                )
                "volume_up" -> Command.SystemControl(SystemAction.VOLUME_UP)
                "volume_down" -> Command.SystemControl(SystemAction.VOLUME_DOWN)
                "volume_mute" -> Command.SystemControl(SystemAction.VOLUME_MUTE)
                "volume_50" -> Command.SystemControl(SystemAction.VOLUME_50)
                "brightness_up" -> Command.SystemControl(SystemAction.BRIGHTNESS_UP)
                "brightness_down" -> Command.SystemControl(SystemAction.BRIGHTNESS_DOWN)
                "auto_rotate_on" -> Command.SystemControl(SystemAction.AUTO_ROTATE_ON)
                "auto_rotate_off" -> Command.SystemControl(SystemAction.AUTO_ROTATE_OFF)
                "take_screenshot" -> Command.Screenshot
                "open_app" -> Command.OpenApp(
                    appName = args.optString("app_name", ""),
                    packageName = args.optString("package_name", "")
                )
                "close_app" -> Command.CloseApp(args.optString("app_name", ""))
                "navigate" -> Command.Navigate(args.optString("destination", ""))
                "open_takeout" -> Command.OpenTakeout
                // ===== UI 自动化（依赖无障碍服务） =====
                "tap" -> Command.UiTap(args.optInt("x"), args.optInt("y"))
                "tap_text" -> args.optString("text")
                    .takeIf { it.isNotBlank() }?.let { Command.UiTapText(it) }
                "swipe" -> Command.UiSwipe(
                    args.optInt("x1"), args.optInt("y1"),
                    args.optInt("x2"), args.optInt("y2"),
                    args.optInt("duration", 300)
                )
                "scroll" -> {
                    val dir = args.optString("direction", "down").lowercase()
                    val direction = when (dir) {
                        "up" -> ScrollDirection.UP
                        "left" -> ScrollDirection.LEFT
                        "right" -> ScrollDirection.RIGHT
                        else -> ScrollDirection.DOWN
                    }
                    Command.UiScroll(direction)
                }
                "input_text" -> args.optString("text")
                    .takeIf { it.isNotBlank() }?.let { Command.UiInputText(it) }
                "press_back" -> Command.UiPressBack
                "press_home" -> Command.UiPressHome
                "long_press" -> Command.UiLongPress(
                    args.optDouble("x").toFloat(),
                    args.optDouble("y").toFloat(),
                    args.optLong("duration", 500)
                )
                else -> null
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + handler)
    private var collectJob: Job? = null

    override fun start() {
        if (collectJob?.isActive == true) return
        collectJob = scope.launch {
            bus.on<AppEvent.AiReplyFinished>().collect { event ->
                try {
                    handleAiReply(event)
                } catch (e: Exception) {
                    LingShuLog.e(TAG, "\u5904\u7406 AI \u56de\u590d\u5f02\u5e38, traceId=${event.traceId}", e)
                }
            }
        }
        LingShuLog.i(TAG, "AiReplyToControlBridge started")
    }

    override fun stop() {
        collectJob?.cancel()
        collectJob = null
        scope.cancel()
        LingShuLog.i(TAG, "AiReplyToControlBridge stopped")
    }

    private suspend fun handleAiReply(event: AppEvent.AiReplyFinished) {
        val reply = event.reply
        val userInput = event.userInput
        val traceId = event.traceId

        // 防双执行守卫：CommandSyncer 已在 ChatViewModel 中对"用户原文可解析"的指令
        // 同步执行过（识别→执行→覆盖回复），这里再执行一遍会导致 startActivity 等操作重复触发。
        // 因此：用户原文能解析为具体指令（非 Unknown）时直接跳过本 Bridge。
        // Bridge 仅保留两类场景：多步 [TOOL_CALL] 序列、从 AI 回复文本中提取指令。
        if (userInput.isNotBlank() && commandParser.parse(userInput) !is Command.Unknown) {
            LingShuLog.i(TAG, "[$traceId] 用户输入已由 CommandSyncer 独立执行（input=${userInput.take(60)}），跳过 Bridge 避免双执行")
            return
        }

        var anyToolSucceeded = false

        // 1. \u5148\u5c1d\u8bd5\u4ece LLM \u56de\u590d\u4e2d\u89e3\u6790 [TOOL_CALL] \u6807\u8bb0
        val toolCalls = parseToolCalls(reply)
        if (toolCalls.isNotEmpty()) {
            LingShuLog.i(TAG, "[$traceId] \u89e3\u6790\u5230 ${toolCalls.size} \u4e2a\u5de5\u5177\u8c03\u7528")
            for (toolCall in toolCalls) {
                val ok = executeToolCall(toolCall, traceId, userInput)
                if (ok) anyToolSucceeded = true
            }
            // \u53ea\u8981\u6709\u4e00\u6761\u6210\u529f\u6267\u884c\u5c31\u4e0d\u518d\u91cd\u590d\uff1b\u5168\u90e8\u5931\u6548\u5219\u964d\u7ea7\u5173\u952e\u8bcd\u89e3\u6790\u515c\u5e95
            if (anyToolSucceeded) return
            LingShuLog.w(TAG, "[$traceId] \u5de5\u5177\u8c03\u7528\u5168\u90e8\u672a\u751f\u6548\uff0c\u964d\u7ea7\u4e3a\u5173\u952e\u8bcd\u89e3\u6790\u515c\u5e95")
        }

        // 2. fallback\uff1a\u7528\u5173\u952e\u8bcd\u89e3\u6790\u5668\u5c1d\u8bd5\u5339\u914d AI \u56de\u590d\u4e2d\u7684\u6307\u4ee4\u6587\u672c
        var command = commandParser.parse(userInput.ifBlank { reply })
        if (command is Command.Unknown) {
            command = commandParser.parse(reply)
        }
        if (command is Command.Unknown) {
            // \u5c1d\u8bd5\u4ece AI \u56de\u590d\u4e2d\u63d0\u53d6\u5bfc\u822a\u76ee\u7684\u5730
            val navMatch = Regex("(?:\u5bfc\u822a\u5230|\u5bfc\u822a\u81f3|\u5bfc\u822a\u53bb|\u524d\u5f80|\u53bb)(.+)").find(reply)
            if (navMatch != null) {
                val dest = navMatch.groupValues[1].trim().trimEnd('.', ',', '\u3002', '\uff0c')
                if (dest.isNotEmpty()) {
                    command = Command.Navigate(dest)
                }
            }
            // \u5c1d\u8bd5\u5339\u914d\u5916\u5356\u5173\u952e\u8bcd
            if (command is Command.Unknown && reply.contains("\u5916\u5356")) {
                command = Command.OpenTakeout
            }
        }
        if (command !is Command.Unknown) {
            LingShuLog.i(TAG, "[$traceId] \u5173\u952e\u8bcd\u5339\u914d\u5230\u6307\u4ee4: $command")
            executeCommand(command, traceId, reply.take(100))
        }
    }

    private suspend fun executeToolCall(
        json: JSONObject,
        traceId: String,
        userInput: String
    ): Boolean {
        val action = json.optString("action").lowercase()
        val args = json.optJSONObject("args") ?: JSONObject()
        var command = mapToolCallToCommand(action, args)

        // open_app package_name 为空时，按 appName 走三级兜底：
        //   1) ISystemControl.getPackageNameByAppName（静态 APP_PACKAGE_MAP + PackageManager 模糊匹配）
        //   2) commandParser.parse("打开$appName") 再解析一遍
        //   3) 仍失败返回 false，交给整体 fallback 走 userInput 关键词解析
        if (command is Command.OpenApp && command.packageName.isBlank()) {
            val appName = command.appName
            if (appName.isBlank()) {
                LingShuLog.w(TAG, "[$traceId] open_app 缺少 app_name 和 package_name，跳过")
                bus.emit(AppEvent.CommandExecuted(
                    command = action,
                    success = false,
                    traceId = traceId
                ))
                return false
            }
            val pkg = systemControl.getPackageNameByAppName(appName).ifBlank {
                (commandParser.parse("打开$appName") as? Command.OpenApp)
                    ?.packageName?.takeIf { it.isNotBlank() } ?: ""
            }
            if (pkg.isNotBlank()) {
                command = Command.OpenApp(appName = appName, packageName = pkg)
                LingShuLog.i(TAG, "[$traceId] open_app 兜底解析到包名: $appName -> $pkg")
            } else {
                LingShuLog.w(TAG, "[$traceId] open_app 未映射到包名: appName=$appName，交由关键词 fallback")
                bus.emit(AppEvent.CommandExecuted(
                    command = action,
                    success = false,
                    traceId = traceId
                ))
                return false
            }
        }

        if (command == null) {
            LingShuLog.w(TAG, "[$traceId] \u672a\u77e5\u5de5\u5177\u8c03\u7528 action=$action")
            bus.emit(AppEvent.CommandExecuted(
                command = action,
                success = false,
                traceId = traceId
            ))
            return false
        }

        executeCommand(command, traceId, action)
        return true
    }

    private suspend fun executeCommand(command: Command, traceId: String, commandDesc: String) {
        val result = commandExecutor.execute(command)
        val success = result is com.lingshu.core.common.error.Result.Success
        LingShuLog.i(TAG, "[$traceId] \u6307\u4ee4\u6267\u884c ${if (success) "\u6210\u529f" else "\u5931\u8d25"}: $commandDesc")

        bus.emit(AppEvent.CommandExecuted(
            command = commandDesc,
            success = success,
            traceId = traceId
        ))

        // \u6210\u529f\u540e\u751f\u6210\u89c4\u8303\u77ed\u53e5\uff0c\u8986\u76d6 AI \u56de\u590d\uff08\u5931\u8d25\u4fdd\u7559\u539f\u59cb\u5185\u5bb9\uff0c\u907f\u514d\u8bef\u5bfc\u7528\u6237\uff09
        if (success) {
            buildCanonicalReply(command)?.let { canonical ->
                bus.emit(AppEvent.AssistantReplyOverridden(
                    canonicalReply = canonical,
                    traceId = traceId
                ))
            }
        }
    }

    /** \u6839\u636e\u6267\u884c\u6210\u529f\u7684\u6307\u4ee4\u751f\u6210\u89c4\u8303\u77ed\u53e5\uff08null=\u65e0\u9700\u8986\u76d6\uff09 */
    private fun buildCanonicalReply(command: Command): String? = when (command) {
        is Command.OpenApp -> {
            val name = command.appName.takeIf { it.isNotBlank() }
                ?: commandExecutor.appDisplayName(command.packageName)
                ?: "\u5e94\u7528"
            "${name}\u5e94\u7528\u5df2\u6253\u5f00"
        }
        is Command.CloseApp -> {
            val name = command.appName.takeIf { it.isNotBlank() } ?: "\u5e94\u7528"
            "${name}\u5df2\u5173\u95ed"
        }
        is Command.Navigate -> "\u5df2\u4e3a\u60a8\u6253\u5f00\u5bfc\u822a\uff0c\u76ee\u7684\u5730\uff1a${command.destination}"
        Command.OpenTakeout -> "\u5916\u5356\u5e94\u7528\u5df2\u6253\u5f00"
        Command.Screenshot -> "\u5df2\u4e3a\u60a8\u622a\u5c4f"
        is Command.SystemControl -> when (command.action) {
            SystemAction.WIFI_ON -> "Wi-Fi \u5df2\u5f00\u542f"
            SystemAction.WIFI_OFF -> "Wi-Fi \u5df2\u5173\u95ed"
            SystemAction.BLUETOOTH_ON -> "\u84dd\u7259\u5df2\u5f00\u542f"
            SystemAction.BLUETOOTH_OFF -> "\u84dd\u7259\u5df2\u5173\u95ed"
            SystemAction.FLASHLIGHT_ON -> "\u624b\u7535\u7b52\u5df2\u5f00\u542f"
            SystemAction.FLASHLIGHT_OFF -> "\u624b\u7535\u7b52\u5df2\u5173\u95ed"
            SystemAction.VOLUME_UP -> "\u97f3\u91cf\u5df2\u8c03\u9ad8"
            SystemAction.VOLUME_DOWN -> "\u97f3\u91cf\u5df2\u8c03\u4f4e"
            SystemAction.VOLUME_MUTE -> "\u97f3\u91cf\u5df2\u9759\u97f3"
            SystemAction.VOLUME_50 -> "\u97f3\u91cf\u5df2\u8c03\u6574\u5230 50%"
            SystemAction.BRIGHTNESS_UP -> "\u4eae\u5ea6\u5df2\u8c03\u9ad8"
            SystemAction.BRIGHTNESS_DOWN -> "\u4eae\u5ea6\u5df2\u8c03\u4f4e"
            SystemAction.AUTO_ROTATE_ON -> "\u81ea\u52a8\u65cb\u8f6c\u5df2\u5f00\u542f"
            SystemAction.AUTO_ROTATE_OFF -> "\u81ea\u52a8\u65cb\u8f6c\u5df2\u5173\u95ed"
            else -> null
        }
        is Command.UiTap -> "\u5df2\u70b9\u51fb\u5c4f\u5e55 (${command.x}, ${command.y})"
        is Command.UiTapText -> "\u5df2\u70b9\u51fb\u300c${command.text}\u300d"
        is Command.UiSwipe -> "\u5df2\u5b8c\u6210\u6ed1\u52a8\u64cd\u4f5c"
        is Command.UiScroll -> when (command.direction) {
            ScrollDirection.UP -> "\u5df2\u5411\u4e0a\u6eda\u52a8\u4e00\u5c4f"
            ScrollDirection.DOWN -> "\u5df2\u5411\u4e0b\u6eda\u52a8\u4e00\u5c4f"
            ScrollDirection.LEFT -> "\u5df2\u5411\u5de6\u6eda\u52a8\u4e00\u5c4f"
            ScrollDirection.RIGHT -> "\u5df2\u5411\u53f3\u6eda\u52a8\u4e00\u5c4f"
        }
        is Command.UiInputText -> "\u5df2\u8f93\u5165\u6587\u672c"
        Command.UiPressBack -> "\u5df2\u8fd4\u56de"
        Command.UiPressHome -> "\u5df2\u56de\u5230\u684c\u9762"
        is Command.UiLongPress -> "\u5df2\u957f\u6309\u5c4f\u5e55 (${command.x}, ${command.y})"
        is Command.WebSearch -> "\u6b63\u5728\u641c\u7d22\uff1a${command.query}"
        Command.PlayMusic -> "\u6b63\u5728\u64ad\u653e\u97f3\u4e50"
        is Command.SetAlarm -> if (command.hour != null) "\u95f9\u949f\u5df2\u8bbe\u7f6e" else "\u5df2\u6253\u5f00\u95f9\u949f"
        Command.OpenCamera -> "\u76f8\u673a\u5df2\u6253\u5f00"
        is Command.MakeCall -> "\u6b63\u5728\u62e8\u6253\u7535\u8bdd"
        is Command.SendSms -> "\u6b63\u5728\u53d1\u9001\u77ed\u4fe1"
        // 三大复合场景：null = 交给 CommandSyncer.buildCanonicalReply 生成完整的合规文案
        is Command.OrderTakeout,
        is Command.SendChatMessage,
        is Command.CallRide -> null
        is Command.AppAction, is Command.Unknown -> null
    }
}
