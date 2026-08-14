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
        /** \u5de5\u5177\u6e05\u5355\uff1a\u58f0\u660e LLM \u53ef\u8c03\u7528\u7684\u7cfb\u7edf\u63a7\u5236\u80fd\u529b\u53ca\u8f93\u51fa\u683c\u5f0f */
        private val TOOL_PROMPT_SECTION = """

=== \u53ef\u7528\u5de5\u5177 ===
\u4f60\u53ef\u4ee5\u5728\u56de\u590d\u4e2d\u5d4c\u5165\u5de5\u5177\u8c03\u7528\u6807\u8bb0\u6765\u63a7\u5236\u624b\u673a\u7cfb\u7edf\u529f\u80fd\u3002\u683c\u5f0f\uff1a
[TOOL_CALL]{"action":"<action_name>","args":{<\u53c2\u6570>}}[/TOOL_CALL]

\u53ef\u7528 action\uff1a
- set_wifi: args={"on":true/false}
- set_bluetooth: args={"on":true/false}
- set_flashlight: args={"on":true/false}
- volume_up / volume_down / volume_mute / volume_50
- brightness_up / brightness_down
- auto_rotate_on / auto_rotate_off
- take_screenshot

界面自动化（需用户已开启无障碍服务，作用于当前前台应用；无障碍未开启时调用会失败）：
- tap: args={"x":<int>,"y":<int>} 点击指定坐标
- tap_text: args={"text":"<控件文字>"} 按控件文字/内容描述点击（优先使用，比坐标可靠）
- swipe: args={"x1":<int>,"y1":<int>,"x2":<int>,"y2":<int>,"duration":<int>} 从(x1,y1)滑动到(x2,y2)
- scroll: args={"direction":"up|down|left|right"} 按方向滚动一屏
- input_text: args={"text":"<文本>"} 向当前焦点输入框输入文本（需先点击输入框使其获焦）
- press_back: 无参数，按返回键
- press_home: 无参数，按 Home 键
- long_press: args={"x":<float>,"y":<float>,"duration":<long>} \u5728\u5750\u6807\u957f\u6309\u6307\u5b9a\u6beb\u79d2
- open_app: args={"app_name":"<\u540d\u79f0>","package_name":"<\u53ef\u7a7a\u4e0d\u586b\uff0c\u670d\u52a1\u7aef\u4f1a\u81ea\u52a8\u6620\u5c04\u5305\u540d>"}
- close_app: args={"app_name":"<\u540d\u79f0>"}
- navigate: args={"destination":"<\u76ee\u7684\u5730\u540d\u79f0\u6216\u5730\u5740>"}
- open_takeout: \u65e0\u53c2\u6570\uff0c\u6253\u5f00\u5916\u5356\u5e94\u7528

\u89c4\u5219\uff1a
1. \u4ec5\u5728\u7528\u6237\u660e\u786e\u8981\u6c42\u63a7\u5236\u7cfb\u7edf\u529f\u80fd\u65f6\u4f7f\u7528\u5de5\u5177\u8c03\u7528
2. \u5de5\u5177\u8c03\u7528\u6807\u8bb0\u524d\u540e\u53ef\u4ee5\u6709\u6b63\u5e38\u6587\u5b57\u56de\u590d\uff0c\u4f46\u53e3\u6c14\u8981\u7b80\u6d01\uff0c\u7981\u7528\u8ffd\u95ee\u3001\u7981\u7528emoji\u3001\u7981\u7528\u8ba9\u7528\u6237\u201c\u968f\u65f6\u8bf4\u54e6\u201d\u8fd9\u7c7b\u586b\u996a\u8bdd
3. \u4e00\u6b21\u56de\u590d\u53ef\u5305\u542b\u591a\u4e2a\u5de5\u5177\u8c03\u7528
4. \u5982\u679c\u7528\u6237\u8bf7\u6c42\u4e0d\u660e\u786e\uff0c\u5148\u786e\u8ba4\u540e\u518d\u8c03\u7528
5. \u6253\u5f00\u5e94\u7528\u65f6\uff0c\u6587\u5b57\u56de\u590d\u53ea\u9700\u4e00\u53e5\u7b80\u77ed\u7684\u786e\u8ba4\u8bed\u5373\u53ef\uff0c\u683c\u5f0f\uff1a\u201c<app_name>\u5e94\u7528\u5df2\u6253\u5f00\u201d\uff0c\u4e0d\u8981\u518d\u52a0\u5176\u4ed6\u52a0\u6cb9\u6216\u63d0\u793a
"""
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

        LingShuLog.i(TAG, "${tracePrefix}\u5f00\u59cb\u88c5\u914dPrompt, historySize=${history.size}")

        val systemPromptBuilder = StringBuilder()
        val ragSources = mutableListOf<String>()
        var memoryLines = 0
        var personaTokens = 0
        var ragChunkCount = 0

        val step1Start = System.currentTimeMillis()
        val personaPrompt = try {
            personaService.generateSystemPrompt()
        } catch (e: Exception) {
            LingShuLog.w(TAG, "${tracePrefix}Step1 \u4eba\u683cPrompt\u751f\u6210\u5931\u8d25, \u4f7f\u7528\u7a7a\u503c", e)
            ""
        }
        systemPromptBuilder.append(personaPrompt)
        personaTokens = personaPrompt.toByteArray().size
        LingShuLog.i(
            TAG,
            "${tracePrefix}Step1 \u4eba\u683c\u7cfb\u7edfPrompt\u6ce8\u5165\u5b8c\u6210, bytes=$personaTokens, cost=${System.currentTimeMillis() - step1Start}ms"
        )

        // Step1.5: \u6ce8\u5165\u5de5\u5177\u6e05\u5355\uff08\u8ba9 LLM \u77e5\u9053\u53ef\u8c03\u7528\u7684\u7cfb\u7edf\u63a7\u5236\u80fd\u529b\uff09
        val step15Start = System.currentTimeMillis()
        systemPromptBuilder.append(TOOL_PROMPT_SECTION)
        LingShuLog.i(
            TAG,
            "${tracePrefix}Step1.5 \u5de5\u5177\u6e05\u5355\u6ce8\u5165\u5b8c\u6210, bytes=${TOOL_PROMPT_SECTION.toByteArray().size}, cost=${System.currentTimeMillis() - step15Start}ms"
        )

        val step2Start = System.currentTimeMillis()
        val memoryPrompt = try {
            memoryService.buildContextPrompt()
        } catch (e: Exception) {
            LingShuLog.w(TAG, "${tracePrefix}Step2 \u957f\u671f\u8bb0\u5fc6\u6784\u5efa\u5931\u8d25, \u4f7f\u7528\u7a7a\u503c", e)
            ""
        }
        if (memoryPrompt.isNotBlank()) {
            if (systemPromptBuilder.isNotEmpty()) {
                systemPromptBuilder.append("\n\n")
            }
            systemPromptBuilder.append("=== \u957f\u671f\u8bb0\u5fc6 ===\n")
            systemPromptBuilder.append(memoryPrompt)
            memoryLines = memoryPrompt.lines().size
        }
        LingShuLog.i(
            TAG,
            "${tracePrefix}Step2 \u957f\u671f\u8bb0\u5fc6\u6ce8\u5165\u5b8c\u6210, lines=$memoryLines, bytes=${memoryPrompt.toByteArray().size}, cost=${System.currentTimeMillis() - step2Start}ms"
        )

        val step3Start = System.currentTimeMillis()
        val ragChunks = try {
            when (val searchResult = ragService.search(userInput)) {
                is Result.Success -> searchResult.data
                is Result.Error -> {
                    LingShuLog.w(TAG, "${tracePrefix}Step3 RAG\u641c\u7d22\u5931\u8d25: ${searchResult.code}")
                    emptyList()
                }
            }
        } catch (e: Exception) {
            LingShuLog.w(TAG, "${tracePrefix}Step3 RAG\u641c\u7d22\u5f02\u5e38", e)
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
            systemPromptBuilder.append("=== \u76f8\u5173\u77e5\u8bc6\u5e93\u53c2\u8003 ===\n")
            filteredRag.forEachIndexed { index, chunk ->
                val source = buildChunkSource(chunk, index)
                systemPromptBuilder.append("[\u53c2\u8003${index + 1}] $source\n")
                systemPromptBuilder.append(chunk.text)
                systemPromptBuilder.append("\n\n")
                ragSources.add(source)
            }
            ragChunkCount = filteredRag.size
        }
        LingShuLog.i(
            TAG,
            "${tracePrefix}Step3 RAG\u6ce8\u5165\u5b8c\u6210, chunks=$ragChunkCount, sources=$ragSources, cost=${System.currentTimeMillis() - step3Start}ms"
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
            "${tracePrefix}Step4 \u5386\u53f2\u5bf9\u8bdd\u6ce8\u5165\u5b8c\u6210, historyRounds=${recentHistory.size}, cost=${System.currentTimeMillis() - step4Start}ms"
        )

        val finalSystemPrompt = truncateIfNeeded(systemPrompt, messages)
        if (finalSystemPrompt != systemPrompt) {
            LingShuLog.w(
                TAG,
                "${tracePrefix}Prompt\u8d85\u51fa\u957f\u5ea6\u9650\u5236, \u5df2\u622a\u65ad systemPrompt: ${systemPrompt.toByteArray().size} -> ${finalSystemPrompt.toByteArray().size} bytes"
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
            "${tracePrefix}Prompt\u88c5\u914d\u5b8c\u6210, totalCost=${totalCost}ms, systemPromptBytes=${finalSystemPrompt.toByteArray().size}, messageCount=${messages.size}, meta=$meta"
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
