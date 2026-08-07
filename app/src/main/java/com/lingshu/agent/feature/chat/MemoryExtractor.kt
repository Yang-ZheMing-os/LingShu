package com.lingshu.agent.feature.chat

import com.lingshu.agent.core.database.entity.MemoryEntity

/**
 * 长期记忆提取器（模块5）
 *
 * 从用户消息和 AI 回复中提取长期记忆，
 * 使用正则匹配中文句式分类为：偏好 / 厌恶 / 习惯 / 事实。
 *
 * 匹配规则：
 * - "我(喜欢|爱|热爱|偏好|钟情)" → 偏好
 * - "我(不|讨厌|厌恶|拒绝|排斥)" → 厌恶
 * - "我(习惯|通常|总是|经常|一般)" → 习惯
 * - "我(是|在|住|工作|从事|来自)" → 事实
 */
object MemoryExtractor {

    private data class Pattern(
        val regex: Regex,
        val category: String
    )

    private val patterns = listOf(
        Pattern(Regex("我(喜欢|爱|热爱|偏好|钟情|最爱|最爱吃|最喜欢).{1,30}"), "偏好"),
        Pattern(Regex("我(不喜欢|不爱|讨厌|厌恶|拒绝|排斥|不吃|不喝|受不了).{1,30}"), "厌恶"),
        Pattern(Regex("我(习惯|通常|总是|经常|一般|每天|每次).{1,30}"), "习惯"),
        Pattern(Regex("我(是|在|住|工作|从事|来自|今年)\\S.{0,25}"), "事实")
    )

    /**
     * 从用户消息和 AI 回复中提取长期记忆
     *
     * @param userMessage 用户发送的消息
     * @param aiReply AI 的回复
     * @param personaId 当前激活的人格ID；null则创建全局记忆（用户姓名、设备信息等）
     * @return 提取到的记忆列表
     */
    fun extract(userMessage: String, aiReply: String, personaId: String? = null): List<MemoryEntity> {
        val texts = listOfNotNull(
            userMessage.takeIf { it.isNotBlank() },
            aiReply.takeIf { it.isNotBlank() }
        )

        val results = mutableListOf<MemoryEntity>()

        texts.forEach { text ->
            patterns.forEach { pattern ->
                pattern.regex.findAll(text).forEach { match ->
                    val sentence = match.value.trim()
                    if (sentence.length in 3..50) {
                        results.add(
                            MemoryEntity(
                                content = normalizeMemory(sentence),
                                category = pattern.category,
                                source = text.take(200),
                                personaId = personaId,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    }
                }
            }
        }

        return results.distinctBy { it.content }
    }

    /**
     * 标准化记忆内容：去除多余空格，统一句号结尾
     */
    private fun normalizeMemory(raw: String): String {
        val cleaned = raw.replace(Regex("\\s+"), "").trim()
        return if (cleaned.endsWith("。")) cleaned else "$cleaned。"
    }
}
