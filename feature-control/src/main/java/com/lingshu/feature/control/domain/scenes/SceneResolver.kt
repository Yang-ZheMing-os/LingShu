package com.lingshu.feature.control.domain.scenes

import com.lingshu.feature.control.domain.Command

/**
 * 用户输入 + 匹配到的场景 → 逐步骤把 slots 抽出来，把每个 step 翻译为 Command。
 * 解析器失败/缺槽位 → 返回 [ScenePlan.MissingSlot] 让 UI 向用户追问。
 */
interface SceneResolver {
    suspend fun resolve(userInput: String): SceneMatch?
}

sealed class SceneMatch {
    /** 场景 + 解析出的槽位值 + 翻译好的可执行 steps */
    data class Ok(
        val scene: GenericScene,
        val filledSlots: Map<String, String>,
        val commands: List<Command>,
        val progressTexts: List<String>
    ) : SceneMatch()

    /** 需要追问用户补一个 slot */
    data class MissingSlot(
        val scene: GenericScene,
        val slotName: String,
        val askPrompt: String,
        val partialSlots: Map<String, String>
    ) : SceneMatch()

    /** 槽位有了，但某一步转 Command 失败（例：联系人查不到） */
    data class StepError(
        val scene: GenericScene,
        val stepId: String,
        val message: String
    ) : SceneMatch()
}
