package com.lingshu.feature.control.data.scenes

import com.lingshu.core.common.log.LingShuLog
import com.lingshu.feature.control.domain.ChatChannel
import com.lingshu.feature.control.domain.Command
import com.lingshu.feature.control.domain.ISystemControl
import com.lingshu.feature.control.domain.scenes.GenericScene
import com.lingshu.feature.control.domain.scenes.ISceneRepository
import com.lingshu.feature.control.domain.scenes.SceneMatch
import com.lingshu.feature.control.domain.scenes.SceneResolver
import com.lingshu.feature.control.domain.scenes.SlotSpec
import com.lingshu.feature.control.domain.scenes.StepActionType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SceneResolverImpl @Inject constructor(
    private val repo: ISceneRepository,
    private val systemControl: ISystemControl
) : SceneResolver {

    companion object {
        private const val TAG = "SceneResolver"
    }

    override suspend fun resolve(userInput: String): SceneMatch? {
        val raw = userInput.trim()
        if (raw.isEmpty()) return null
        val scenes = repo.allScenes().sortedByDescending { it.priority }
        val scene = pickScene(raw, scenes) ?: return null
        val slots = extractSlots(raw, scene)
        // 检查缺失槽位
        scene.slots.forEach { spec ->
            if (spec.optional) return@forEach
            val v = slots[spec.name]?.takeIf { it.isNotBlank() } ?: spec.defaultValue
            if (v == null || v.isBlank()) {
                LingShuLog.i(TAG, "命中场景 ${scene.sceneId} 但缺 slot=${spec.name}，反问：${spec.askPrompt}")
                return SceneMatch.MissingSlot(
                    scene = scene,
                    slotName = spec.name,
                    askPrompt = spec.askPrompt.ifBlank { "请告诉我 ${spec.name}" },
                    partialSlots = slots
                )
            } else {
                slots[spec.name] = v
            }
        }
        return translate(scene, slots)
    }

    // ------- 匹配 intent -------
    private fun pickScene(raw: String, scenes: List<GenericScene>): GenericScene? {
        val lowered = raw.lowercase()
        for (s in scenes) {
            if (s.intentKeywords.any { kw -> kw.isNotBlank() && kw.lowercase() in lowered }) return s
        }
        // 兜底：启发式关键词（更宽松）—— "给XX说YY" → SEND_CHAT_SCENE；"打车去XX" → CALL_RIDE；"导航去" → NAV
        if (Regex("""给\s*\S.*说|告诉\s*\S|发.*微信|发.*QQ|发短信""").containsMatchIn(raw)) {
            return scenes.firstOrNull { it.sceneId == "builtin_send_chat" }
        }
        if (Regex("""打.?车|叫.?车|约.?车|打辆""").containsMatchIn(raw)) {
            return scenes.firstOrNull { it.sceneId == "builtin_call_ride" }
        }
        if (Regex("""导航|带我去|指路|去.*怎么走""").containsMatchIn(raw)) {
            return scenes.firstOrNull { it.sceneId == "builtin_nav" }
        }
        if (Regex("""点外卖|订餐|点个|订个|外卖""").containsMatchIn(raw)) {
            return scenes.firstOrNull { it.sceneId == "builtin_order_takeout" }
        }
        return null
    }

    // ------- 抽槽 -------
    private fun extractSlots(raw: String, scene: GenericScene): MutableMap<String, String> {
        val out = mutableMapOf<String, String>()
        for (slot in scene.slots) {
            val v = slot.extractionRegex
                ?.let { r -> Regex(r).find(raw)?.groupValues?.drop(1)?.firstOrNull() }
                ?: extractByHints(raw, scene, slot)
                ?: extractBySlotName(raw, slot)
            if (!v.isNullOrBlank()) out[slot.name] = v
        }
        // 渠道推断（SEND_CHAT）
        if (scene.sceneId == "builtin_send_chat" && !out.containsKey("channel")) {
            out["channel"] = inferChannel(raw).name
        }
        if (scene.sceneId == "builtin_call_ride" && !out.containsKey("carType")) {
            out["carType"] = inferCarType(raw) ?: ""
        }
        return out
    }

    private fun extractByHints(raw: String, scene: GenericScene, slot: SlotSpec): String? {
        val allHints = scene.steps.flatMap { it.extractHints[slot.name].orEmpty() }
            .ifEmpty { defaultHints(slot.name) }
        for (hint in allHints) {
            val idx = raw.indexOf(hint)
            if (idx < 0) continue
            val after = raw.substring(idx + hint.length).trim()
                .trimStart { it == '，' || it == '。' || it == '：' || it == ':' || it == ' ' }
                // 取到下一个标点为止
                .takeWhile { it !in setOf('，', '。', '；', ';', ',', '？', '?', '!', '！', '\n') }
                .trim()
            if (after.isNotBlank()) return after
        }
        return null
    }

    private fun defaultHints(slotName: String): List<String> = when (slotName) {
        "contact" -> listOf("给", "告诉", "通知", "给", "对", "微信", "短信", "QQ", "飞书")
        "message" -> listOf("说", "说一声", "告诉", "说：", "说:", "内容是", "消息是", "：")
        "destination" -> listOf("去", "到", "往", "目的地是", "打车去", "打车到", "导航去", "导航到")
        "carType" -> listOf("打个", "叫一辆", "打")
        "food" -> listOf("点个", "买个", "来个", "订个", "点一份", "点", "订")
        "address" -> listOf("送到", "送到地址", "地址是")
        "channel" -> listOf("用", "通过")
        else -> emptyList()
    }

    private fun extractBySlotName(raw: String, slot: SlotSpec): String? = when (slot.name) {
        "message" -> run {
            // "今晚不回去了" — 末尾自然语言（如果包含"说/告诉"且之后有内容，前面抽不到会走到这里）
            Regex("""(?:说|告诉|发的|内容是)[:：]?\s*(.+)$""").find(raw)?.groupValues?.get(1)
        }
        else -> null
    }

    private fun inferChannel(raw: String): ChatChannel = when {
        "微信" in raw || "wechat" in raw.lowercase() -> ChatChannel.WECHAT
        "qq" in raw.lowercase() -> ChatChannel.QQ
        "短信" in raw || "sms" in raw.lowercase() -> ChatChannel.SMS
        "飞书" in raw -> ChatChannel.SMS   // ChatChannel 只有这三种，飞书兜底短信（或在后续扩展 ChatChannel 枚举）
        else -> ChatChannel.WECHAT
    }

    private fun inferCarType(raw: String): String? {
        val types = listOf("快车", "专车", "拼车", "顺风车", "出租车", "的士", "优享")
        return types.firstOrNull { it in raw }
    }

    // ------- 把 GenericScene + filled slots 翻译为 Command 列表 -------
    private fun translate(scene: GenericScene, slots: MutableMap<String, String>): SceneMatch {
        val progress = mutableListOf<String>()
        val cmds = mutableListOf<Command>()
        scene.steps.forEachIndexed { i, step ->
            val label = substitute(step.humanLabel, slots)
            progress += "第${i + 1}步：${label.ifBlank { step.stepId }}"
            val bindings = step.slotBindings.mapValues { (_, v) -> substitute(v, slots) }
            val cmdR = runCatching { stepToCommand(step.action, bindings) }
            if (cmdR.isFailure) {
                LingShuLog.w(TAG, "步骤翻译失败 step=${step.stepId}", cmdR.exceptionOrNull())
                return SceneMatch.StepError(
                    scene = scene,
                    stepId = step.stepId,
                    message = cmdR.exceptionOrNull()?.message ?: "步骤翻译失败"
                )
            }
            cmdR.getOrNull()?.let { cmds += it }
        }
        return SceneMatch.Ok(
            scene = scene,
            filledSlots = slots,
            commands = cmds,
            progressTexts = progress
        )
    }

    private fun substitute(template: String, slots: Map<String, String>): String {
        var out = template
        // 支持 {xxx} 模板；对 "或 默认值" 文案做回退：{carType 或 快车}
        val regex = Regex("""\{([^{}]+)\}""")
        regex.findAll(template).forEach { m ->
            val raw = m.groupValues[1]
            val parts = raw.split("或", "｜", "|", limit = 2).map { it.trim() }
            val slotName = parts[0]
            val default = parts.getOrNull(1) ?: ""
            val v = slots[slotName]?.takeIf { it.isNotBlank() } ?: default
            out = out.replace(m.value, v)
        }
        return out
    }

    private fun stepToCommand(action: StepActionType, bindings: Map<String, String>): Command? = when (action) {
        StepActionType.OPEN_APP -> {
            val appName = bindings["appName"] ?: error("OPEN_APP 缺 appName")
            val pkg = systemControl.getPackageNameByAppName(appName)
            Command.OpenApp(appName = appName, packageName = pkg)
        }
        StepActionType.CLOSE_APP -> {
            val appName = bindings["appName"] ?: error("CLOSE_APP 缺 appName")
            Command.CloseApp(appName = appName)
        }
        StepActionType.SEND_CHAT_MESSAGE -> {
            val channelRaw = bindings["channel"] ?: ChatChannel.WECHAT.name
            val channel = runCatching { ChatChannel.valueOf(channelRaw.uppercase()) }
                .getOrElse { ChatChannel.WECHAT }
            val contact = bindings["contact"] ?: error("SEND_CHAT_MESSAGE 缺 contact")
            val msg = bindings["message"] ?: ""
            if (channel == ChatChannel.SMS) {
                Command.SendSms(phoneNumberOrContact = contact, message = msg)
            } else {
                Command.SendChatMessage(
                    channel = channel,
                    contactNameOrPhone = contact,
                    message = msg
                )
            }
        }
        StepActionType.SEND_SMS -> {
            Command.SendSms(
                phoneNumberOrContact = bindings["contact"] ?: error("SEND_SMS 缺 contact"),
                message = bindings["message"] ?: ""
            )
        }
        StepActionType.MAKE_CALL -> {
            Command.MakeCall(
                phoneNumberOrContact = bindings["contact"] ?: error("MAKE_CALL 缺 contact")
            )
        }
        StepActionType.CALL_RIDE -> {
            Command.CallRide(
                destination = bindings["destination"] ?: error("CALL_RIDE 缺 destination"),
                carTypePref = bindings["carType"]?.takeIf { it.isNotBlank() }
            )
        }
        StepActionType.NAVIGATE -> {
            Command.Navigate(destination = bindings["destination"] ?: error("NAVIGATE 缺 destination"))
        }
        StepActionType.OPEN_TAKEOUT -> Command.OpenTakeout
        StepActionType.ORDER_TAKEOUT -> {
            Command.OrderTakeout(
                foodKeyword = bindings["food"] ?: error("ORDER_TAKEOUT 缺 food"),
                addressHint = bindings["address"]?.takeIf { it.isNotBlank() }
            )
        }
        StepActionType.TAKE_SCREENSHOT -> Command.Screenshot
        StepActionType.OPEN_CAMERA -> Command.OpenCamera
        StepActionType.PLAY_MUSIC -> Command.PlayMusic
        StepActionType.SET_ALARM -> {
            Command.SetAlarm(
                hour = bindings["hour"]?.toIntOrNull() ?: 8,
                minute = bindings["minute"]?.toIntOrNull() ?: 0,
                label = bindings["label"]
            )
        }
        StepActionType.WEB_SEARCH -> Command.WebSearch(query = bindings["query"] ?: error("WEB_SEARCH 缺 query"))
        StepActionType.SYSTEM_CONTROL -> null  // 场景里不直接用
        StepActionType.CONFIRM_WITH_USER -> null
    }
}
