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
    @IoDispatcher private val handler: CoroutineDispatcher
) : StartableBridge {
    companion object {
        private const val TAG = "AiReplyToControlBridge"
        private const val TOOL_CALL_OPEN = "[TOOL_CALL]"
        private const val TOOL_CALL_CLOSE = "[/TOOL_CALL]"
        private val TOOL_REGEX = Regex("""\[TOOL_CALL]\s*(\{.*?\})\s*\[/TOOL_CALL]""", RegexOption.DOT_MATCHES_ALL)

        /** \u4ece\u6587\u672c\u4e2d\u63d0\u53d6\u6240\u6709 [TOOL_CALL]{...}[/TOOL_CALL] \u6807\u8bb0\u7684 JSON \u5185\u5bb9 */
        internal fun parseToolCalls(text: String): List<JSONObject> {
            val results = mutableListOf<JSONObject>()
            for (match in TOOL_REGEX.findAll(text)) {
                try {
                    val json = JSONObject(match.groupValues[1])
                    results.add(json)
                } catch (e: Exception) {
                    LingShuLog.w(TAG, "\u89e3\u6790\u5de5\u5177\u8c03\u7528 JSON \u5931\u8d25: ${match.value}", e)
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

        // 1. \u5148\u5c1d\u8bd5\u4ece LLM \u56de\u590d\u4e2d\u89e3\u6790 [TOOL_CALL] \u6807\u8bb0
        val toolCalls = parseToolCalls(reply)
        if (toolCalls.isNotEmpty()) {
            LingShuLog.i(TAG, "[$traceId] \u89e3\u6790\u5230 ${toolCalls.size} \u4e2a\u5de5\u5177\u8c03\u7528")
            for (toolCall in toolCalls) {
                executeToolCall(toolCall, traceId)
            }
            return
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

    private suspend fun executeToolCall(json: JSONObject, traceId: String) {
        val action = json.optString("action").lowercase()
        val args = json.optJSONObject("args") ?: JSONObject()
        val command = mapToolCallToCommand(action, args) ?: run {
            LingShuLog.w(TAG, "[$traceId] \u672a\u77e5\u5de5\u5177\u8c03\u7528 action=$action")
            bus.emit(AppEvent.CommandExecuted(
                command = action,
                success = false,
                traceId = traceId
            ))
            return
        }

        executeCommand(command, traceId, action)
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
    }
}
