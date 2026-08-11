package com.lingshu.feature.memory.data

import com.lingshu.feature.memory.domain.Memory
import com.lingshu.feature.memory.domain.MemoryType

class MemoryExtractor {

    private data class TriggerRule(
        val triggers: List<String>,
        val type: MemoryType,
        val extractPattern: Regex? = null
    )

    private val rules = listOf(
        TriggerRule(
            triggers = listOf("我喜欢", "我喜爱", "我偏爱"),
            type = MemoryType.PREFERENCE
        ),
        TriggerRule(
            triggers = listOf("我不喜欢", "我讨厌"),
            type = MemoryType.PREFERENCE
        ),
        TriggerRule(
            triggers = listOf("我习惯", "我经常"),
            type = MemoryType.HABIT
        ),
        TriggerRule(
            triggers = listOf("我今年", "我是", "我家"),
            type = MemoryType.FACT
        ),
        TriggerRule(
            triggers = listOf("你记得", "别忘了"),
            type = MemoryType.FACT
        )
    )

    fun extract(userInput: String, aiResponse: String): List<Memory> {
        val memories = mutableListOf<Memory>()
        val text = "$userInput $aiResponse"

        for (rule in rules) {
            for (trigger in rule.triggers) {
                if (text.contains(trigger)) {
                    val content = extractMemoryContent(text, trigger)
                    if (content.isNotBlank()) {
                        val memory = Memory(
                            content = content,
                            type = rule.type,
                            source = "dialogue",
                            importance = calculateImportance(rule.type, content)
                        )
                        memories.add(memory)
                    }
                    break
                }
            }
        }

        return memories
    }

    private fun extractMemoryContent(text: String, trigger: String): String {
        val triggerIndex = text.indexOf(trigger)
        if (triggerIndex == -1) return ""

        val startIndex = triggerIndex
        var endIndex = text.length

        val sentenceEnders = listOf("。", "！", "？", "，", "；", "\n")
        for (ender in sentenceEnders) {
            val idx = text.indexOf(ender, startIndex + trigger.length)
            if (idx != -1 && idx < endIndex) {
                endIndex = idx
            }
        }

        return text.substring(startIndex, endIndex).trim()
    }

    private fun calculateImportance(type: MemoryType, content: String): Int {
        var base = when (type) {
            MemoryType.PREFERENCE -> 6
            MemoryType.HABIT -> 5
            MemoryType.FACT -> 7
            MemoryType.RELATIONSHIP -> 8
            MemoryType.EMOTIONAL -> 7
        }

        if (content.length > 30) base += 1
        if (content.contains("重要") || content.contains("关键")) base += 2

        return base.coerceIn(1, 10)
    }
}
