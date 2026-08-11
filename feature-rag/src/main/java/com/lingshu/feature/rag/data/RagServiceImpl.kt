package com.lingshu.feature.rag.data

import com.lingshu.core.common.error.Result
import com.lingshu.core.common.error.ErrorCodes
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.core.data.datastore.AppPreferences
import com.lingshu.core.data.llm.ChatMessage
import com.lingshu.core.data.llm.LlmConfig
import com.lingshu.core.data.llm.LlmRouter
import com.lingshu.core.data.llm.ModelProviderType
import com.lingshu.feature.rag.domain.Chunk
import com.lingshu.feature.rag.domain.Document
import com.lingshu.feature.rag.domain.IRagService
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface IEmbeddingEngine {
    suspend fun embed(text: String): FloatArray
    suspend fun embedBatch(texts: List<String>): List<FloatArray>
}

interface IEmbeddingGemma : IEmbeddingEngine

/**
 * 基于文本特征哈希的确定性 Embedding 引擎。
 * 使用字符 bigram + 词频哈希映射到固定维度向量，相似文本产生相似向量。
 * 虽不如真实 neural embedding 精确，但检索结果有意义且完全离线。
 */
@Singleton
class HashBasedEmbeddingEngine @Inject constructor() : IEmbeddingEngine {

    private val dim = 384

    override suspend fun embed(text: String): FloatArray {
        val vec = FloatArray(dim)
        val normalized = text.lowercase().trim()
        if (normalized.isEmpty()) return vec

        // 字符 bigram 特征
        for (i in 0 until normalized.length - 1) {
            val bigram = normalized.substring(i, i + 2)
            val idx = (bigram.hashCode() and 0x7FFFFFFF) % dim
            vec[idx] += 1f
        }
        // 单词特征（按空格/标点分词）
        val words = normalized.split(Regex("[\\s\\p{Punct}]+"))
            .filter { it.isNotBlank() }
        for (w in words) {
            val idx = (w.hashCode() and 0x7FFFFFFF) % dim
            vec[idx] += 2f
            // 词的首字符也作为特征
            if (w.isNotEmpty()) {
                val cIdx = (w[0].code and 0x7FFFFFFF) % dim
                vec[cIdx] += 0.5f
            }
        }
        // 中文单字特征（每个汉字作为独立 token）
        for (ch in normalized) {
            if (ch.code > 0x4E00 && ch.code < 0x9FFF) {
                val idx = (ch.code and 0x7FFFFFFF) % dim
                vec[idx] += 1.5f
            }
        }

        normalize(vec)
        return vec
    }

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> {
        return texts.map { embed(it) }
    }

    private fun normalize(vector: FloatArray) {
        var sum = 0f
        for (v in vector) sum += v * v
        val norm = kotlin.math.sqrt(sum)
        if (norm > 0) {
            for (i in vector.indices) vector[i] /= norm
        }
    }
}

@Singleton
class RagServiceImpl @Inject constructor(
    private val embeddingEngine: IEmbeddingEngine,
    private val llmRouter: LlmRouter,
    private val appPreferences: AppPreferences
) : IRagService {

    private val documents = mutableListOf<Document>()
    private val chunks = mutableListOf<ChunkWithEmbedding>()

    private val chunkSize = 512
    private val chunkOverlap = 64
    private val maxChunks = 200
    private val topK = 5
    private val similarityThreshold = 0.15f

    override suspend fun uploadDocument(file: File): Result<Unit> {
        return try {
            LingShuLog.d(TAG, "上传文档: ${file.name}")

            val content = readDocumentContent(file)
            val documentChunks = splitIntoChunks(content)

            if (documentChunks.isEmpty()) {
                return Result.Error(code = ErrorCodes.DOCUMENT_PARSE_FAILED, message = "文档内容为空")
            }

            val docId = "doc_${UUID.randomUUID().toString().take(8)}"
            val document = Document(
                id = docId,
                name = file.name,
                size = file.length(),
                chunkCount = documentChunks.size,
                uploadedAt = System.currentTimeMillis()
            )

            documents.add(0, document)

            val embeddings = embeddingEngine.embedBatch(documentChunks)
            documentChunks.forEachIndexed { index, text ->
                chunks.add(
                    0,
                    ChunkWithEmbedding(
                        text = text,
                        embedding = embeddings[index],
                        documentId = docId
                    )
                )
            }

            LingShuLog.i(TAG, "文档上传成功: ${file.name}, 分块数: ${documentChunks.size}")
            Result.Success(Unit)
        } catch (e: Exception) {
            LingShuLog.e(TAG, "文档上传失败", e)
            Result.Error(code = ErrorCodes.DOCUMENT_PARSE_FAILED, message = e.message ?: "文档上传失败", cause = e)
        }
    }

    private fun readDocumentContent(file: File): String {
        return try {
            file.readText()
        } catch (e: Exception) {
            LingShuLog.w(TAG, "读取文档失败，使用文件名: ${file.name}")
            "文档: ${file.name}"
        }
    }

    private fun splitIntoChunks(text: String): List<String> {
        val result = mutableListOf<String>()
        if (text.isBlank()) return result

        if (text.length <= chunkSize) {
            result.add(text)
            return result
        }

        var position = 0
        while (position < text.length && result.size < maxChunks) {
            val end = (position + chunkSize).coerceAtMost(text.length)
            val chunk = text.substring(position, end)
            result.add(chunk)
            if (end >= text.length) break
            position += chunkSize - chunkOverlap
        }
        return result
    }

    override fun listDocuments(): List<Document> {
        return documents.toList()
    }

    override suspend fun deleteDocument(documentId: String): Result<Unit> {
        return try {
            val iterator = documents.iterator()
            while (iterator.hasNext()) {
                val doc = iterator.next()
                if (doc.id == documentId) {
                    iterator.remove()
                    break
                }
            }
            chunks.removeAll { it.documentId == documentId }
            LingShuLog.d(TAG, "删除文档: $documentId")
            Result.Success(Unit)
        } catch (e: Exception) {
            LingShuLog.e(TAG, "删除文档失败", e)
            Result.Error(code = ErrorCodes.UNKNOWN_ERROR, message = e.message ?: "操作失败", cause = e)
        }
    }

    override suspend fun search(query: String): Result<List<Chunk>> {
        return try {
            LingShuLog.d(TAG, "搜索查询: ${query.take(30)}...")

            if (chunks.isEmpty()) {
                return Result.Success(emptyList())
            }

            val queryEmbedding = embeddingEngine.embed(query)

            val scoredChunks = chunks.map { chunk ->
                val score = cosineSimilarity(queryEmbedding, chunk.embedding)
                ScoredChunk(chunk.text, score, chunk.documentId)
            }

            val filtered = scoredChunks
                .filter { it.score >= similarityThreshold }
                .sortedByDescending { it.score }
                .take(topK)

            val result = filtered.map {
                Chunk(text = it.text, score = it.score, documentId = it.documentId)
            }

            LingShuLog.d(TAG, "搜索完成，找到 ${result.size} 个相关段落, 最高分=${filtered.firstOrNull()?.score ?: 0f}")
            Result.Success(result)
        } catch (e: Exception) {
            LingShuLog.e(TAG, "搜索失败", e)
            Result.Error(code = ErrorCodes.UNKNOWN_ERROR, message = e.message ?: "操作失败", cause = e)
        }
    }

    override suspend fun ask(query: String): Result<String> {
        return try {
            LingShuLog.d(TAG, "问答查询: ${query.take(30)}...")

            val searchResult = search(query)
            if (searchResult.isError) {
                val error = searchResult as Result.Error
                return Result.Error(error.code, error.message, error.cause)
            }

            val relevantChunks = (searchResult as Result.Success).data

            if (relevantChunks.isEmpty()) {
                return Result.Success("抱歉，在知识库中没有找到与\"$query\"相关的内容。请先上传相关文档。")
            }

            val context = relevantChunks.joinToString("\n\n---\n\n") { it.text }
            val apiKey = appPreferences.apiKey.first()

            if (apiKey.isBlank()) {
                val fallback = buildFallbackAnswer(query, relevantChunks)
                return Result.Success(fallback + "\n\n（提示：在设置中配置 API Key 后可获得更智能的回答）")
            }

            val config = LlmConfig(
                provider = ModelProviderType.DEEPSEEK,
                apiKey = apiKey,
                baseUrl = "https://api.deepseek.com/v1",
                modelName = "deepseek-chat",
                temperature = 0.3f,
                maxTokens = 1024,
                timeoutSeconds = 30
            )

            val messages = listOf(
                ChatMessage(
                    role = "system",
                    content = "你是一个知识库问答助手。根据以下参考资料回答用户问题。" +
                            "如果资料中没有答案，请明确说明。回答要简洁准确。\n\n" +
                            "参考资料：\n$context"
                ),
                ChatMessage(role = "user", content = query)
            )

            LingShuLog.d(TAG, "调用 LLM 进行 RAG 问答, chunks=${relevantChunks.size}")
            val llmResult = llmRouter.chat(messages, config, traceId = "rag_ask")

            when (llmResult) {
                is Result.Success -> {
                    LingShuLog.i(TAG, "RAG 问答成功, 回复长度=${llmResult.data.length}")
                    Result.Success(llmResult.data)
                }
                is Result.Error -> {
                    LingShuLog.w(TAG, "LLM 调用失败，使用 fallback 回答: ${llmResult.code}")
                    Result.Success(buildFallbackAnswer(query, relevantChunks))
                }
            }
        } catch (e: Exception) {
            LingShuLog.e(TAG, "问答失败", e)
            Result.Error(code = ErrorCodes.UNKNOWN_ERROR, message = e.message ?: "操作失败", cause = e)
        }
    }

    private fun buildFallbackAnswer(query: String, chunks: List<Chunk>): String {
        val sb = StringBuilder()
        sb.appendLine("根据知识库中 ${chunks.size} 个相关段落，关于\"$query\"的信息如下：")
        sb.appendLine()
        chunks.forEachIndexed { i, chunk ->
            sb.appendLine("【段落 ${i + 1}】")
            sb.appendLine(chunk.text.take(200))
            sb.appendLine()
        }
        return sb.toString()
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dotProduct = 0f
        for (i in a.indices) {
            dotProduct += a[i] * b[i]
        }
        return dotProduct
    }

    data class ChunkWithEmbedding(
        val text: String,
        val embedding: FloatArray,
        val documentId: String
    )

    data class ScoredChunk(
        val text: String,
        val score: Float,
        val documentId: String
    )

    companion object {
        private const val TAG = "RagService"
    }
}
