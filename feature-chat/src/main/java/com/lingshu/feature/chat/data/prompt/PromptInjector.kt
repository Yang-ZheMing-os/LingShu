package com.lingshu.feature.chat.data.prompt

import com.lingshu.core.common.log.LingShuLog
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PromptInjector @Inject constructor() {

    companion object {
        private const val TAG = "PromptInjector"
        const val ROLE_SYSTEM = "system"
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }

    fun inject(assembly: PromptAssembly, traceId: String = ""): List<ChatMessage> {
        val tracePrefix = if (traceId.isNotBlank()) "[$traceId] " else ""
        val result = mutableListOf<ChatMessage>()

        if (assembly.systemPrompt.isNotBlank()) {
            result.add(
                ChatMessage(
                    role = ROLE_SYSTEM,
                    content = assembly.systemPrompt
                )
            )
            LingShuLog.d(
                TAG,
                "${tracePrefix}注入System Prompt, bytes=${assembly.systemPrompt.toByteArray().size}"
            )
        } else {
            LingShuLog.w(TAG, "${tracePrefix}System Prompt为空，跳过注入")
        }

        result.addAll(assembly.messages)

        val roleCounts = result.groupingBy { it.role }.eachCount()
        val totalChars = result.sumOf { it.content.length }
        LingShuLog.i(
            TAG,
            "${tracePrefix}Prompt注入完成, totalMessages=${result.size}, roles=$roleCounts, totalChars=$totalChars, meta=${assembly.injectionMeta}"
        )

        validateStructure(result, traceId)

        return result
    }

    fun toLlmChatMessages(messages: List<ChatMessage>): List<com.lingshu.feature.chat.data.ChatMessage> {
        return messages.map {
            com.lingshu.feature.chat.data.ChatMessage(
                role = it.role,
                content = it.content
            )
        }
    }

    private fun validateStructure(messages: List<ChatMessage>, traceId: String) {
        val tracePrefix = if (traceId.isNotBlank()) "[$traceId] " else ""

        if (messages.isEmpty()) {
            LingShuLog.w(TAG, "${tracePrefix}警告：最终消息列表为空")
            return
        }

        val lastMessage = messages.last()
        if (lastMessage.role != ROLE_USER) {
            LingShuLog.w(
                TAG,
                "${tracePrefix}警告：最后一条消息不是user角色，而是${lastMessage.role}"
            )
        }

        var hasSystem = false
        messages.forEachIndexed { index, msg ->
            when {
                index == 0 && msg.role == ROLE_SYSTEM -> hasSystem = true
                msg.role == ROLE_SYSTEM && index != 0 -> {
                    LingShuLog.w(
                        TAG,
                        "${tracePrefix}警告：第${index}条消息出现非首部的system角色"
                    )
                }
            }
        }

        if (!hasSystem) {
            LingShuLog.w(TAG, "${tracePrefix}警告：消息列表中未包含system prompt")
        }
    }
}
