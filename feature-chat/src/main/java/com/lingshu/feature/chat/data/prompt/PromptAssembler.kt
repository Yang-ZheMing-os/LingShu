package com.lingshu.feature.chat.data.prompt

import com.lingshu.core.common.error.Result
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.core.data.datastore.AppPreferences
import com.lingshu.core.common.event.Message
import com.lingshu.feature.memory.domain.IMemoryService
import com.lingshu.feature.persona.domain.IPersonaService
import com.lingshu.feature.rag.domain.Chunk
import com.lingshu.feature.rag.domain.IRagService
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

interface IPromptAssembler {
    suspend fun assemble(
        userInput: String,
        history: List<Message>,
        traceId: String = ""
    ): PromptAssembly
}

data class PromptAssembly(
    val systemPrompt: String,
    val messages: List<ChatMessage>,
    val injectionMeta: InjectionMeta
)

data class InjectionMeta(
    val memoryLines: Int,
    val personaTokens: Int,
    val ragChunkCount: Int,
    val ragSources: List<String>,
    val buildCostMs: Long
)

data class ChatMessage(
    val role: String,
    val content: String
)

@Singleton
class PromptAssembler @Inject constructor(
    private val personaService: IPersonaService,
    private val memoryService: IMemoryService,
    private val ragService: IRagService,
    private val appPreferences: AppPreferences
) : IPromptAssembler {

    companion object {
        private const val TAG = "PromptAssembler"
        private const val RAG_SIMILARITY_THRESHOLD = 0.65f
        private const val RAG_MAX_CHUNKS = 5
        private const val HISTORY_MAX_ROUNDS = 20
    }

    private object LlmConfig {
        const val maxTokens: Int = 4096
    }

    override suspend fun assemble(
        userInput: String,
        history: List<Message>,
        traceId: String
    ): PromptAssembly {
        val startTime = System.currentTimeMillis()
        val tracePrefix = if (traceId.isNotBlank()) "[$traceId] " else ""

        LingShuLog.i(TAG, "${tracePrefix}开始装配Prompt, historySize=${history.size}")

        val systemPromptBuilder = StringBuilder()
        val ragSources = mutableListOf<String>()
        var memoryLines = 0
        var personaTokens = 0
        var ragChunkCount = 0

        val step1Start = System.currentTimeMillis()
        val personaPrompt = try {
            personaService.generateSystemPrompt()
        } catch (e: Exception) {
            LingShuLog.w(TAG, "${tracePrefix}Step1 人格Prompt生成失败, 使用空值", e)
            ""
        }
        systemPromptBuilder.append(personaPrompt)
        personaTokens = personaPrompt.toByteArray().size
        LingShuLog.i(
            TAG,
            "${tracePrefix}Step1 人格系统Prompt注入完成, bytes=$personaTokens, cost=${System.currentTimeMillis() - step1Start}ms"
        )

        val step2Start = System.currentTimeMillis()
        val memoryPrompt = try {
            memoryService.buildContextPrompt()
        } catch (e: Exception) {
            LingShuLog.w(TAG, "${tracePrefix}Step2 长期记忆构建失败, 使用空值", e)
            ""
        }
        if (memoryPrompt.isNotBlank()) {
            if (systemPromptBuilder.isNotEmpty()) {
                systemPromptBuilder.append("\n\n")
            }
            systemPromptBuilder.append("=== 长期记忆 ===\n")
            systemPromptBuilder.append(memoryPrompt)
            memoryLines = memoryPrompt.lines().size
        }
        LingShuLog.i(
            TAG,
            "${tracePrefix}Step2 长期记忆注入完成, lines=$memoryLines, bytes=${memoryPrompt.toByteArray().size}, cost=${System.currentTimeMillis() - step2Start}ms"
        )

        val step3Start = System.currentTimeMillis()
        val ragChunks = try {
            when (val searchResult = ragService.search(userInput)) {
                is Result.Success -> searchResult.data
                is Result.Error -> {
                    LingShuLog.w(TAG, "${tracePrefix}Step3 RAG搜索失败: ${searchResult.code}")
                    emptyList()
                }
            }
        } catch (e: Exception) {
            LingShuLog.w(TAG, "${tracePrefix}Step3 RAG搜索异常", e)
            emptyList()
        }
        val filteredRag = ragChunks
            .filter { it.score >= RAG_SIMILARITY_THRESHOLD }
            .sortedByDescending { it.score }
            .take(RAG_MAX_CHUNKS)

        if (filteredRag.isNotEmpty()) {
            if (systemPromptBuilder.isNotEmpty()) {
                systemPromptBuilder.append("\n\n")
            }
            systemPromptBuilder.append("=== 相关知识库参考 ===\n")
            filteredRag.forEachIndexed { index, chunk ->
                val source = buildChunkSource(chunk, index)
                systemPromptBuilder.append("[参考${index + 1}] $source\n")
                systemPromptBuilder.append(chunk.text)
                systemPromptBuilder.append("\n\n")
                ragSources.add(source)
            }
            ragChunkCount = filteredRag.size
        }
        LingShuLog.i(
            TAG,
            "${tracePrefix}Step3 RAG注入完成, chunks=$ragChunkCount, sources=$ragSources, cost=${System.currentTimeMillis() - step3Start}ms"
        )

        val systemPrompt = systemPromptBuilder.toString()

        val step4Start = System.currentTimeMillis()
        val recentHistory = history.takeLast(HISTORY_MAX_ROUNDS)
        val messages = mutableListOf<ChatMessage>()
        recentHistory.forEach { msg ->
            messages.add(
                ChatMessage(
                    role = if (msg.isUser) "user" else "assistant",
                    content = msg.content
                )
            )
        }
        messages.add(ChatMessage(role = "user", content = userInput))
        LingShuLog.i(
            TAG,
            "${tracePrefix}Step4 历史对话注入完成, historyRounds=${recentHistory.size}, cost=${System.currentTimeMillis() - step4Start}ms"
        )

        val finalSystemPrompt = truncateIfNeeded(systemPrompt, messages)
        if (finalSystemPrompt != systemPrompt) {
            LingShuLog.w(
                TAG,
                "${tracePrefix}Prompt超出长度限制, 已截断 systemPrompt: ${systemPrompt.toByteArray().size} -> ${finalSystemPrompt.toByteArray().size} bytes"
            )
        }

        val totalCost = System.currentTimeMillis() - startTime
        val meta = InjectionMeta(
            memoryLines = memoryLines,
            personaTokens = personaTokens,
            ragChunkCount = ragChunkCount,
            ragSources = ragSources,
            buildCostMs = totalCost
        )

        LingShuLog.i(
            TAG,
            "${tracePrefix}Prompt装配完成, totalCost=${totalCost}ms, systemPromptBytes=${finalSystemPrompt.toByteArray().size}, messageCount=${messages.size}, meta=$meta"
        )

        return PromptAssembly(
            systemPrompt = finalSystemPrompt,
            messages = messages,
            injectionMeta = meta
        )
    }

    private fun buildChunkSource(chunk: Chunk, index: Int): String {
        return "doc:${chunk.documentId}(score=${String.format("%.2f", chunk.score)})"
    }

    private fun truncateIfNeeded(
        systemPrompt: String,
        messages: List<ChatMessage>
    ): String {
        val totalBytes = systemPrompt.toByteArray().size +
                messages.sumOf { it.content.toByteArray().size }
        val maxAllowed = LlmConfig.maxTokens * 2

        if (totalBytes <= maxAllowed) {
            return systemPrompt
        }

        val overflow = totalBytes - maxAllowed
        val systemBytes = systemPrompt.toByteArray().size
        val newSystemBytes = (systemBytes - overflow).coerceAtLeast(systemBytes / 2)
        val ratio = newSystemBytes.toDouble() / systemBytes.toDouble()

        val newLength = (systemPrompt.length * ratio).toInt().coerceAtLeast(1)
        return systemPrompt.take(newLength)
    }
}
