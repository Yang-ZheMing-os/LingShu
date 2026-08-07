package com.lingshu.agent.feature.knowledge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.lingshu.agent.feature.model.MessageRole
import com.lingshu.agent.feature.model.ModelMessage
import com.lingshu.agent.feature.model.ModelResponse
import com.lingshu.agent.feature.model.ModelRouter
import com.lingshu.agent.feature.model.ResponseStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RAG 知识库管理器
 *
 * 核心工作流：
 *
 * ┌──────────────┐     ┌─────────────────┐     ┌──────────────────┐     ┌──────────────┐
 * │ 放入文档     │────▶│ DocumentProcessor │────▶│ EmbeddingProvider │────▶│  VectorStore  │
 * │ (File/Text)  │     │   清洗+切片      │     │   生成向量嵌入    │     │  存储+检索    │
 * └──────────────┘     └─────────────────┘     └──────────────────┘     └──────┬───────┘
 *                                                                               │
 *                                                                               │ 检索topK
 *                                                                               ▼
 * ┌──────────────┐     ┌─────────────────┐     ┌──────────────────┐     ┌──────────────┐
 * │ 提问(question)│────▶│  问题向量化    │────▶│ 上下文组装(Prompt)│────▶│ ModelRouter   │
 * │              │     │                 │     │                  │     │  chat 生成答案 │
 * └──────────────┘     └─────────────────┘     └──────────────────┘     └──────────────┘
 *
 * 主要职责：
 * 1. 文档入库：接收 File/文本，调用 DocumentProcessor → 向量化 → VectorStore
 * 2. 文档管理：删除、列出所有文档、查询索引状态
 * 3. 问答调用：ask(question) → 检索topK → 组装RAG Prompt → ModelRouter.chat()
 * 4. Ollama/Termux 安装引导检测：提供 isOllamaAvailable / setupOllamaGuide
 * 5. 事件总线：上传进度、索引状态、问答历史等通过 Flow 暴露给 UI
 */
@Singleton
class KnowledgeManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vectorStore: VectorStore,
    private val embeddingProvider: EmbeddingProvider,
    private val documentProcessor: DocumentProcessor,
    private val modelRouter: ModelRouter
) {

    companion object {
        private const val TAG = "KnowledgeManager"

        /** RAG 检索的默认 TopK */
        const val DEFAULT_TOP_K = 5

        /** 组装 Prompt 时最多引入的上下文字符数上限（防止爆上下文窗口） */
        const val MAX_CONTEXT_CHARS = 6000

        /** Ollama 本地服务默认地址 */
        private const val OLLAMA_PACKAGE_NAME = "com.termux"
    }

    // ==================== 协程作用域 ====================
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ==================== 状态 Flow ====================

    /** 索引状态：文档总数、切片总数、向量维度等（来自VectorStore的转发） */
    val storeStats: Flow<VectorStoreStats> = vectorStore.observeStats()

    /** 当前是否有入库任务正在运行（用于UI显示loading） */
    private val _isIndexing = MutableStateFlow(false)
    val isIndexing: StateFlow<Boolean> = _isIndexing.asStateFlow()

    /** 上传/入库进度（0~100），正在入库的文件名 */
    private val _indexingProgress = MutableStateFlow(IndexingProgress("", 0))
    val indexingProgress: StateFlow<IndexingProgress> = _indexingProgress.asStateFlow()

    /** 问答历史（保留最近N条） */
    private val _qaHistory = MutableStateFlow<List<QaEntry>>(emptyList())
    val qaHistory: StateFlow<List<QaEntry>> = _qaHistory.asStateFlow()

    /** 通用事件（上传完成、删除完成、RAG失败降级等） */
    private val _events = MutableSharedFlow<KnowledgeEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<KnowledgeEvent> = _events.asSharedFlow()

    // ==================== 文档入库 ====================

    /**
     * 将一个本地文件加入知识库
     *
     * @param file 待处理文件（TXT/MD/PDF）
     * @param docId 可选：自定义文档ID；null则自动生成
     * @return 最终入库的文档ID；失败返回null
     */
    suspend fun addDocumentFile(file: File, docId: String? = null): String? = withContext(Dispatchers.IO) {
        if (_isIndexing.value) {
            Log.w(TAG, "已有入库任务运行中，请稍候重试")
            _events.emit(KnowledgeEvent.IndexingBusy)
            return@withContext null
        }
        if (!documentProcessor.isSupported(file)) {
            _events.emit(KnowledgeEvent.FileTypeNotSupported(file.extension))
            return@withContext null
        }

        _isIndexing.value = true
        _indexingProgress.value = IndexingProgress(file.name, 0)

        try {
            // 1. 处理 + 切片
            Log.d(TAG, "开始处理文件：${file.name}")
            val rawChunks = documentProcessor.processFile(file)
            if (rawChunks.isEmpty()) {
                Log.w(TAG, "文件无有效内容：${file.name}")
                _events.emit(KnowledgeEvent.DocumentEmpty(file.name))
                return@withContext null
            }

            // 2. 如果调用方指定 docId，覆盖所有切片的 docId
            val finalDocId = docId ?: rawChunks.first().docId
            val chunks = if (docId != null) {
                rawChunks.mapIndexed { i, c ->
                    c.copy(
                        docId = finalDocId,
                        id = "${finalDocId}_c$i",
                        chunkIndex = i
                    )
                }
            } else rawChunks

            // 3. 分批向量化 + 入库（每批 32 条，更新进度）
            val batchSize = 32
            val total = chunks.size
            chunks.chunked(batchSize).forEachIndexed { batchIdx, batch ->
                // 填充向量
                val texts = batch.map { it.content }
                val vectors = embeddingProvider.embedBatch(texts)
                val withVec = batch.mapIndexed { i, c -> c.copy(embedding = vectors[i]) }

                vectorStore.addDocuments(withVec)

                val progress = ((batchIdx + 1) * batchSize * 100 / total).coerceAtMost(100)
                _indexingProgress.value = IndexingProgress(file.name, progress)
            }

            _events.emit(KnowledgeEvent.DocumentAdded(finalDocId, file.name, total))
            _qaHistory.tryEmit(_qaHistory.value) // 触发UI重绘
            Log.i(TAG, "文档入库完成：${file.name}，docId=$finalDocId，切片数=$total")
            finalDocId
        } catch (e: Exception) {
            Log.e(TAG, "入库异常: ${e.message}", e)
            _events.emit(KnowledgeEvent.ErrorOccurred("入库失败：${e.message}"))
            null
        } finally {
            _isIndexing.value = false
            _indexingProgress.value = IndexingProgress("", 100)
        }
    }

    /**
     * 将纯文本内容加入知识库（来自剪贴板、聊天记录、手动粘贴等）
     *
     * @param content 文本内容
     * @param docId 自定义文档ID
     * @param sourceName 来源标签（如"剪贴板"、"对话导入"）
     */
    suspend fun addDocumentText(
        content: String,
        docId: String,
        sourceName: String = "纯文本"
    ): Boolean = withContext(Dispatchers.IO) {
        if (_isIndexing.value) {
            _events.emit(KnowledgeEvent.IndexingBusy)
            return@withContext false
        }
        if (content.isBlank()) return@withContext false

        _isIndexing.value = true
        _indexingProgress.value = IndexingProgress(sourceName, 0)

        try {
            val chunks = documentProcessor.processText(content, docId, sourceName)
            if (chunks.isEmpty()) {
                _events.emit(KnowledgeEvent.DocumentEmpty(sourceName))
                return@withContext false
            }

            val batchSize = 32
            val total = chunks.size
            chunks.chunked(batchSize).forEachIndexed { batchIdx, batch ->
                val vectors = embeddingProvider.embedBatch(batch.map { it.content })
                val withVec = batch.mapIndexed { i, c -> c.copy(embedding = vectors[i]) }
                vectorStore.addDocuments(withVec)
                val progress = ((batchIdx + 1) * batchSize * 100 / total).coerceAtMost(100)
                _indexingProgress.value = IndexingProgress(sourceName, progress)
            }

            _events.emit(KnowledgeEvent.DocumentAdded(docId, sourceName, total))
            true
        } catch (e: Exception) {
            Log.e(TAG, "文本入库异常: ${e.message}", e)
            _events.emit(KnowledgeEvent.ErrorOccurred("文本入库失败：${e.message}"))
            false
        } finally {
            _isIndexing.value = false
            _indexingProgress.value = IndexingProgress("", 100)
        }
    }

    /**
     * 删除指定文档（所有切片）
     */
    suspend fun deleteDocument(docId: String): Boolean {
        return try {
            if (vectorStore.hasDocument(docId)) {
                vectorStore.deleteDocument(docId)
                _events.emit(KnowledgeEvent.DocumentDeleted(docId))
                true
            } else {
                Log.w(TAG, "删除失败：文档不存在 $docId")
                false
            }
        } catch (e: Exception) {
            _events.emit(KnowledgeEvent.ErrorOccurred("删除失败：${e.message}"))
            false
        }
    }

    /**
     * 清空整个知识库
     */
    suspend fun clearAll() {
        vectorStore.clearAll()
        _qaHistory.value = emptyList()
        _events.emit(KnowledgeEvent.ClearedAll)
    }

    /**
     * 获取所有已索引的文档ID
     */
    suspend fun listAllDocuments(): List<String> = vectorStore.getAllDocumentIds()

    // ==================== RAG 问答 ====================

    /**
     * 提问（RAG 流程）
     *
     * 流程：
     * 1. 如果 useKnowledge=true → 问题向量化 → 向量库检索 topK → 组装 Context → 构造 Prompt
     * 2. 如果 useKnowledge=false 或 检索结果为空 → 走纯对话 Prompt（降级）
     * 3. 通过 ModelRouter.chat(messages) 获取回答
     * 4. 问答记录写入 _qaHistory（保留最近 50 条）
     */
    suspend fun ask(
        question: String,
        useKnowledge: Boolean = true,
        topK: Int = DEFAULT_TOP_K
    ): ModelResponse = withContext(Dispatchers.Default) {
        if (question.isBlank()) {
            return@withContext ModelResponse.error("问题不能为空")
        }

        val startTime = System.currentTimeMillis()

        // 1. 检索上下文
        val (retrievedChunks, assembledContext) = if (useKnowledge) {
            val results = runCatching {
                vectorStore.search(question, topK)
            }.getOrDefault(emptyList())
            val context = assembleRagContext(results, MAX_CONTEXT_CHARS)
            results to context
        } else {
            emptyList<SearchResult>() to ""
        }

        // 2. 构建 Prompt
        val messages = buildRagMessages(question, assembledContext, useKnowledge)

        // 3. 调用模型
        val response: ModelResponse = try {
            modelRouter.chat(messages)
        } catch (e: Exception) {
            Log.e(TAG, "RAG chat 异常: ${e.message}", e)
            ModelResponse.error("调用模型异常：${e.message}")
        }

        val latency = System.currentTimeMillis() - startTime

        // 4. 记录历史（最多保留50条）
        val entry = QaEntry(
            id = "qa_${startTime}",
            question = question,
            answer = if (response.isSuccess) response.content else (response.errorMessage ?: ""),
            useKnowledge = useKnowledge,
            references = retrievedChunks.map { r ->
                QaReference(
                    filename = r.chunk.metadata["filename"] ?: r.chunk.metadata["source"] ?: "未知来源",
                    section = r.chunk.metadata["section"] ?: "",
                    snippet = r.chunk.content.take(120),
                    score = r.similarityScore
                )
            },
            timestamp = startTime,
            latencyMs = latency,
            isSuccess = response.isSuccess,
            provider = response.providerId
        )
        val newHistory = (_qaHistory.value + entry).takeLast(50)
        _qaHistory.value = newHistory

        // 5. 如果是降级/失败，发射事件
        if (!response.isSuccess) {
            _events.emit(KnowledgeEvent.RagAnswerFailed(question, response.errorMessage ?: ""))
        } else if (useKnowledge && retrievedChunks.isEmpty()) {
            _events.emit(KnowledgeEvent.RagNoContextFallback(question))
        }

        // 6. 补齐 latency（如果 Provider 没填）
        if (response.latencyMs == 0L && response.isSuccess) {
            response.copy(latencyMs = latency)
        } else response
    }

    /**
     * 纯语义搜索（不调用模型，仅返回检索到的切片）
     * 供 UI 单独展示「相关文档片段」卡片使用
     */
    suspend fun searchOnly(
        query: String,
        topK: Int = DEFAULT_TOP_K
    ): List<SearchResult> {
        return vectorStore.search(query, topK)
    }

    // ==================== Prompt 组装 ====================

    /**
     * 将检索到的 topK 片段组装为 RAG 上下文字符串
     *
     * 格式：
     * 【文档A 章节X】
     * ... 片段1 ...
     * 相似度：xx
     * ---
     * 【文档B 章节Y】
     * ... 片段2 ...
     */
    private fun assembleRagContext(results: List<SearchResult>, maxChars: Int): String {
        if (results.isEmpty()) return ""
        val sb = StringBuilder(maxChars)
        sb.appendLine("以下是从本地知识库中检索到的可能相关的参考资料（按相似度降序）：")
        sb.appendLine("====================")
        var idx = 1
        for (r in results) {
            val title = r.chunk.metadata["filename"] ?: r.chunk.metadata["source"] ?: "文档"
            val section = r.chunk.metadata["section"]?.let { " - $it" } ?: ""
            val block = buildString {
                appendLine("📄 参考 $idx：$title$section  （相似度：${"%.3f".format(r.similarityScore)}）")
                appendLine(r.chunk.content)
                appendLine("----")
            }
            if (sb.length + block.length > maxChars) {
                // 余下空间装不下，截一部分后跳出
                val room = (maxChars - sb.length).coerceAtLeast(0)
                if (room > 50) {
                    sb.append(block.take(room))
                    sb.appendLine("...[上下文过长，已截断]")
                }
                break
            }
            sb.append(block)
            idx++
        }
        sb.appendLine("====================")
        sb.appendLine("请优先根据以上参考资料回答用户问题。如果参考资料与问题无关或未覆盖，请明确说明并基于你的知识作答。")
        return sb.toString()
    }

    /**
     * 构建最终发送给 ModelRouter 的消息列表
     */
    private fun buildRagMessages(
        question: String,
        context: String,
        useKnowledge: Boolean
    ): List<ModelMessage> {
        val systemPrompt = if (useKnowledge && context.isNotBlank()) {
            """
你是灵枢AI助手。请基于给定的【本地知识库参考资料】回答用户的问题。

回答原则：
1. 以参考资料为首要依据，不编造资料中没有的事实
2. 如果参考资料不包含答案或信息不足，明确告知"知识库中没有相关资料"，然后补充你自己的通用回答
3. 答案中若引用了特定文档，请在括号内注明（如"据文档X章节Y所述..."）
4. 回答简洁、结构化，关键步骤使用有序或无序列表
5. 使用用户的提问语言回答
            """.trimIndent()
        } else {
            "你是灵枢AI助手。请用中文简洁、准确地回答用户的问题。"
        }

        val userPrompt = buildString {
            if (context.isNotBlank()) {
                appendLine(context)
                appendLine()
            }
            append("用户问题：")
            appendLine(question)
        }

        return listOf(
            ModelMessage(role = MessageRole.SYSTEM, content = systemPrompt),
            ModelMessage(role = MessageRole.USER, content = userPrompt)
        )
    }

    // ==================== Ollama / Termux 环境检测 ====================

    /**
     * 检查 Ollama 服务是否可用（通过 HTTP 探活）
     */
    suspend fun isOllamaAvailable(): Boolean {
        return runCatching { embeddingProvider.isAvailable() }.getOrDefault(false)
    }

    /**
     * 检查 Termux 是否已安装（用于引导用户通过 Termux 安装 Ollama）
     */
    fun isTermuxInstalled(): Boolean {
        return try {
            val pm = context.packageManager
            pm.getPackageInfo(OLLAMA_PACKAGE_NAME, 0) != null
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 打开 Termux（如果已安装），否则跳应用商店
     * 返回 true 表示成功跳转，false 表示没装 Termux 也没应用商店可跳
     */
    fun openTermuxOrStore(): Boolean {
        return if (isTermuxInstalled()) {
            val intent = context.packageManager.getLaunchIntentForPackage(OLLAMA_PACKAGE_NAME)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else false
        } else {
            // 打开应用商店搜索 Termux
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("market://search?q=termux")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            } catch (e: Exception) {
                // 连应用商店都没有：打开F-Droid网页
                try {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("https://f-droid.org/packages/com.termux/")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    true
                } catch (e2: Exception) {
                    false
                }
            }
        }
    }

    /**
     * 生成 Ollama 安装引导文本（供 UI 展示）
     */
    fun getOllamaSetupGuide(): String {
        return """
【第一步：安装 Termux】
从 F-Droid 商店安装 Termux（不推荐 Google Play 版本，会过期）。
下载地址：https://f-droid.org/packages/com.termux/

【第二步：在 Termux 中执行以下命令】
1. 更新包管理器：
   pkg update && pkg upgrade -y

2. 安装 Ollama（官方一键脚本）：
   curl -fsSL https://ollama.com/install.sh | sh
   （如果官方脚本不兼容，可尝试 pkg install ollama）

3. 启动 Ollama 服务：
   ollama serve &

4. 拉取嵌入模型（约 274MB）：
   ollama pull nomic-embed-text

5. 拉取对话模型（可选，推荐中文表现好的）：
   ollama pull qwen2.5:7b        # 约 4.7GB
   ollama pull llama3.2:3b       # 约 2.0GB

【第三步：验证】
回到灵枢 App 的知识库页面，点击「检测 Ollama 可用性」即可。
如提示成功，您就可以开始向知识库导入文档了！
        """.trimIndent()
    }

    /**
     * 清除问答历史（不影响知识库索引）
     */
    fun clearQaHistory() {
        _qaHistory.value = emptyList()
    }
}

// ==================== UI 状态 & 事件 数据类 ====================

/** 入库进度 */
data class IndexingProgress(
    val fileName: String,
    val percent: Int
) {
    val isIdle: Boolean get() = fileName.isBlank()
}

/** 问答条目（历史记录） */
data class QaEntry(
    val id: String,
    val question: String,
    val answer: String,
    val useKnowledge: Boolean,
    val references: List<QaReference>,
    val timestamp: Long,
    val latencyMs: Long,
    val isSuccess: Boolean,
    val provider: String?
)

/** 引用来源 */
data class QaReference(
    val filename: String,
    val section: String,
    val snippet: String,
    val score: Float
)

/** 知识库事件 */
sealed class KnowledgeEvent {
    object IndexingBusy : KnowledgeEvent()
    data class FileTypeNotSupported(val ext: String) : KnowledgeEvent()
    data class DocumentEmpty(val name: String) : KnowledgeEvent()
    data class DocumentAdded(val docId: String, val name: String, val chunks: Int) : KnowledgeEvent()
    data class DocumentDeleted(val docId: String) : KnowledgeEvent()
    object ClearedAll : KnowledgeEvent()
    data class ErrorOccurred(val msg: String) : KnowledgeEvent()
    data class RagAnswerFailed(val question: String, val reason: String) : KnowledgeEvent()
    data class RagNoContextFallback(val question: String) : KnowledgeEvent()
}
