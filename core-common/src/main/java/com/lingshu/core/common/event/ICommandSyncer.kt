package com.lingshu.core.common.event

/**
 * 独立、同步的「指令识别→执行→生成规范回复」执行器接口（定义层）。
 *
 * 由 feature-control 模块实现具体的 CommandSyncer，并通过 Hilt @Binds 注入。
 * 放 core-common 是为了解除 feature-chat 与 feature-control 之间的循环依赖：
 * ChatViewModel 只依赖 core-common 的接口，不需要 implementation project(":feature-control")。
 */
interface ICommandSyncer {

    /**
     * 对用户输入做一次同步识别+执行。
     *
     * @param userInput 用户原文（例："打开微信"、"调高亮度"）
     * @return 命中并执行成功时返回规范短句（例："微信应用已打开"），
     *         未识别/执行失败返回 null（调用方继续沿用 LLM 原文回复）
     */
    suspend fun sync(userInput: String): String?

    /**
     * Day3-1：当用户输入是 Unknown（没有 parse 到任何指令）时，
     * 返回 TopN 条可执行示例建议，用来给 AI 回复末尾加"💡 你也可以试试：…"推荐。
     */
    fun topSimilarSuggestions(userInput: String, limit: Int = 5): List<String>

    /**
     * Day3-1：判断用户输入是否真的是 Unknown（没有匹配到任何场景 / 单动作指令）。
     * 用于 ChatViewModel 在 sync 返回 null 时，决定是否追加相似示例建议。
     */
    fun isUnknown(userInput: String): Boolean
}


