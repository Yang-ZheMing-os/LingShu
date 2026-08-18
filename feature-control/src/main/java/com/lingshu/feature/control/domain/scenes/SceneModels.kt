package com.lingshu.feature.control.domain.scenes

/**
 * 一个场景步骤：把用户自然语言里的 slot（{联系人}、{消息内容}、{目的地}…）抽出来后，
 * 映射到一个已经存在的 [com.lingshu.feature.control.domain.Command] 原子动作。
 *
 * 例：给妈妈发微信说"今晚不回去了" → 2 步：
 *   1) OpenApp(appName="微信", packageName=resolve("微信"))
 *   2) SendChatMessage(channel=WECHAT, contact="妈妈", message="今晚不回去了")
 */
enum class StepActionType {
    OPEN_APP,
    CLOSE_APP,
    SEND_CHAT_MESSAGE,
    SEND_SMS,
    MAKE_CALL,
    CALL_RIDE,
    NAVIGATE,
    OPEN_TAKEOUT,
    ORDER_TAKEOUT,
    TAKE_SCREENSHOT,
    OPEN_CAMERA,
    SET_ALARM,
    PLAY_MUSIC,
    WEB_SEARCH,
    SYSTEM_CONTROL,
    /** 显式向用户确认某个 slot，等用户点"继续"再执行后续步骤 */
    CONFIRM_WITH_USER
}

data class SlotSpec(
    /** 占位符名字，例 "contact"、"message"、"destination"、"carType" */
    val name: String,
    /** 当从用户原文抽不出值时，使用这条自然语言反问用户 */
    val askPrompt: String = "",
    /** 正则（可选）；若为空，会在运行时根据 [SceneStep.extractHints] 里的关键词启发式匹配 */
    val extractionRegex: String? = null,
    val optional: Boolean = false,
    val defaultValue: String? = null
)

data class SceneStep(
    val stepId: String,
    val action: StepActionType,
    /** 每一步的说明，展示在执行进度条里，例 "第1步：打开微信" */
    val humanLabel: String,
    /** 本步骤依赖的 slots 顺序，例 listOf("contact", "message") */
    val slotBindings: Map<String, String> = emptyMap(),
    /** 抽 slot 的提示（给内置解析器）：哪些关键词出现时把后面的文本视为该 slot */
    val extractHints: Map<String, List<String>> = emptyMap(),
    /** 步骤成功后输出到回复里的文案 */
    val successLabel: String? = null,
    val needUserConfirm: Boolean = false,
    /** 下一步跳转：null=默认顺序往下；或显式 stepId */
    val nextStepId: String? = null
)

/**
 * 通用场景：把"一段用户指令"拆成"一串 SceneStep"。
 * 这样就不需要硬编码三大场景了——新增一个场景 = 增加一个 GenericScene（支持 UI 动态新增/JSON 导入）。
 */
data class GenericScene(
    val sceneId: String,
    /** 场景名，例 "通过 IM 发消息给某人" */
    val displayName: String,
    /** 内置 / 用户自定义，用于持久化区分 */
    val builtIn: Boolean = false,
    /** 命中 intent 的关键词（用户说的话里命中任意一个即判定为该场景），支持中文、英文、简称 */
    val intentKeywords: List<String> = emptyList(),
    /** 匹配优先级，数字越大越先匹配；默认三大场景优先级高 */
    val priority: Int = 0,
    /** 用户必须填的槽位 */
    val slots: List<SlotSpec> = emptyList(),
    /** 动作步骤（顺序执行） */
    val steps: List<SceneStep> = emptyList(),
    /** 全部步骤成功后的结尾语（支持 {slotName} 模板） */
    val completionText: String = "搞定 ✅"
)
