package com.lingshu.core.common

/**
 * LLM 返回文本中 [TOOL_CALL]...[/TOOL_CALL] 标记清理工具。
 *
 * 聊天气泡、落库消息、TTS 朗读场景共用，避免把 JSON 指令暴露给用户或念出声。
 */
object ToolCallCleaner {

    private val toolCallBlockRegex: Regex =
        Regex("\\[TOOL_CALL](.*?)\\[/TOOL_CALL]", RegexOption.DOT_MATCHES_ALL)

    /**
     * 移除文本中所有 [TOOL_CALL]...[/TOOL_CALL] 标记块，并合并多余空行。
     *
     * 例："好的，我帮你打开设置。\n[TOOL_CALL]{...}[/TOOL_CALL]\n设置应用已打开"
     *   → "好的，我帮你打开设置。\n设置应用已打开"
     */
    fun stripToolCallMarks(text: String?): String {
        if (text.isNullOrBlank()) return ""
        val cleaned = toolCallBlockRegex.replace(text, "")
        return collapseBlankLines(cleaned).trim()
    }

    /**
     * 文本中是否含有 [TOOL_CALL] 标记（仅判断存在性，不解析）。
     */
    fun hasToolCall(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return text.contains("[TOOL_CALL]") && text.contains("[/TOOL_CALL]")
    }

    /** 把两个及以上的连续空行压缩为至多一个空行 */
    private fun collapseBlankLines(s: String): String {
        return s.replace(Regex("(?m)^[ \\t]*\\n+"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
    }
}
