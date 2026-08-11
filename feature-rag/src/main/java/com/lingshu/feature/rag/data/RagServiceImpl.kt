package com.lingshu.feature.rag.data

import com.lingshu.core.common.error.Result
import com.lingshu.core.common.error.ErrorCodes
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.feature.rag.domain.Chunk
import com.lingshu.feature.rag.domain.Document
import com.lingshu.feature.rag.domain.IRagService
import kotlinx.coroutines.delay
import java.io.File
import java.util.UUID
import javax.inject.Inject

interface IEmbeddingEngine {
    suspend fun embed(text: String): FloatArray
    suspend fun embedBatch(texts: List<String>): List<FloatArray>
}

interface IEmbeddingGemma : IEmbeddingEngine

class MockEmbeddingEngine @Inject constructor() : IEmbeddingEngine {
    override suspend fun embed(text: String): FloatArray {
        LingShuLog.d(TAG, "Mock Embedding: 向量化文本 (${text.length} 字符)")
        return FloatArray(384) { (Math.random() * 2 - 1).toFloat() }.also {
            normalize(it)
        }
    }

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> {
        LingShuLog.d(TAG, "Mock Embedding: 批量向量化 ${texts.size} 个文本")
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

    companion object {
        private const val TAG = "MockEmbedding"
    }
}

class RagServiceImpl @Inject constructor(
    private val embeddingEngine: IEmbeddingEngine
) : IRagService {

    private val documents = mutableListOf<Document>()
    private val chunks = mutableListOf<ChunkWithEmbedding>()

    private val chunkSize = 512
    private val chunkOverlap = 64
    private val maxChunks = 200
    private val topK = 5
    private val similarityThreshold = 0.65f

    init {
        val mockDocuments = listOf(
            Document(
                id = "doc_001",
                name = "产品使用手册.pdf",
                size = 2097152,
                chunkCount = 15,
                uploadedAt = System.currentTimeMillis() - 86400000 * 7
            ),
            Document(
                id = "doc_002",
                name = "技术架构文档.docx",
                size = 1048576,
                chunkCount = 8,
                uploadedAt = System.currentTimeMillis() - 86400000 * 3
            )
        )
        documents.addAll(mockDocuments)

        for (doc in mockDocuments) {
            for (i in 0 until doc.chunkCount) {
                chunks.add(
                    ChunkWithEmbedding(
                        text = "这是文档 ${doc.name} 的第 ${i + 1} 个段落内容。这里包含了关于产品功能和使用方法的详细说明。",
                        embedding = FloatArray(384) { (Math.random() * 2 - 1).toFloat() }.also {
                            var sum = 0f
                            for (v in it) sum += v * v
                            val norm = kotlin.math.sqrt(sum)
                            if (norm > 0) for (j in it.indices) it[j] /= norm
                        },
                        documentId = doc.id
                    )
                )
            }
        }
    }

    override suspend fun uploadDocument(file: File): Result<Unit> {
        return try {
            LingShuLog.d(TAG, "上传文档: ${file.name}")

            val content = readDocumentContent(file)
            val documentChunks = splitIntoChunks(content)

            if (documentChunks.isEmpty()) {
                return Result.Error(IllegalArgumentException("文档内容为空"), ErrorCodes.DOCUMENT_PARSE_FAILED)
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
            Result.Error(e, ErrorCodes.DOCUMENT_PARSE_FAILED)
        }
    }

    private fun readDocumentContent(file: File): String {
        return try {
            file.readText()
        } catch (e: Exception) {
            "这是 ${file.name} 的模拟内容。文档包含了丰富的知识信息，可以用于问答检索。"
        }
    }

    private fun splitIntoChunks(text: String): List<String> {
        val result = mutableListOf<String>()

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
            Result.Error(e, ErrorCodes.UNKNOWN_ERROR)
        }
    }

    override suspend fun search(query: String): Result<List<Chunk>> {
        return try {
            LingShuLog.d(TAG, "搜索查询: ${query.take(30)}...")

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

            LingShuLog.d(TAG, "搜索完成，找到 ${result.size} 个相关段落")
            Result.Success(result)
        } catch (e: Exception) {
            LingShuLog.e(TAG, "搜索失败", e)
            Result.Error(e, ErrorCodes.UNKNOWN_ERROR)
        }
    }

    override suspend fun ask(query: String): Result<String> {
        return try {
            LingShuLog.d(TAG, "问答查询: ${query.take(30)}...")

            val searchResult = search(query)
            if (searchResult.isError()) {
                return Result.Error(
                    (searchResult as Result.Error).exception,
                    (searchResult as Result.Error).code
                )
            }

            val relevantChunks = (searchResult as Result.Success).data

            delay(1000)

            val answer = if (relevantChunks.isNotEmpty()) {
                val context = relevantChunks.joinToString("\n\n") { it.text }
                "根据文档内容，关于\"$query\"的回答如下：\n\n" +
                        "这是基于知识库中 ${relevantChunks.size} 个相关段落生成的回答。" +
                        "文档中提到了相关的信息和说明。"
            } else {
                "抱歉，在知识库中没有找到与\"$query\"相关的内容。"
            }

            Result.Success(answer)
        } catch (e: Exception) {
            LingShuLog.e(TAG, "问答失败", e)
            Result.Error(e, ErrorCodes.UNKNOWN_ERROR)
        }
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
