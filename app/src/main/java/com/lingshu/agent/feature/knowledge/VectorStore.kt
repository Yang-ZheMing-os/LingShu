package com.lingshu.agent.feature.knowledge

import android.util.Log
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

// ==================== 核心数据结构 ====================

/**
 * 文档切片（Chunk）
 *
 * @param id 切片唯一ID（由 docId + chunkIndex 生成）
 * @param docId 所属原始文档ID
 * @param content 切片文本内容
 * @param embedding 文本的向量嵌入（FloatArray）
 * @param metadata 元数据：章节名、页码、文件类型、创建时间等
 * @param chunkIndex 在原文档中的序号（从0开始）
 * @param tokenCount 该切片的Token数量（估算值，用于检查切分是否符合512token规则）
 */
data class DocumentChunk(
    val id: String,
    val docId: String,
    val content: String,
    val embedding: FloatArray,
    val metadata: Map<String, String> = emptyMap(),
    val chunkIndex: Int = 0,
    val tokenCount: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DocumentChunk) return false
        if (id != other.id) return false
        if (!embedding.contentEquals(other.embedding)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}

/**
 * 向量搜索结果
 *
 * @param chunk 匹配到的文档切片
 * @param similarityScore 相似度分数（0.0 ~ 1.0，越大越相似）
 */
data class SearchResult(
    val chunk: DocumentChunk,
    val similarityScore: Float
)

/**
 * 向量库统计信息
 *
 * @param totalChunks 总切片数
 * @param totalDocuments 独立文档数
 * @param embeddingDimension 向量维度
 * @param indexSizeBytes 索引占用内存估算（字节）
 */
data class VectorStoreStats(
    val totalChunks: Int,
    val totalDocuments: Int,
    val embeddingDimension: Int,
    val indexSizeBytes: Long
)

// ==================== VectorStore 抽象接口 ====================

/**
 * 向量数据库抽象接口
 *
 * 设计目标：
 * 1. 屏蔽底层向量数据库差异（SQLite-VSS、ChromaDB、内存实现等）
 * 2. 提供统一的 CRUD + 搜索 + 统计 API
 * 3. 所有写操作使用 Mutex 串行化，避免并发损坏索引
 *
 * 实现策略：
 * - 默认实现：InMemoryVectorStore（内存 + 余弦相似度，零依赖，即时可用）
 * - 预留实现：SqliteVssVectorStore（需引入 sqlite-vss 依赖，适合本地持久化）
 * - 预留实现：ChromaDbVectorStore（通过 HTTP 调 ChromaDB，适合远程部署）
 */
interface VectorStore {

    /**
     * 批量添加文档切片到向量库
     * 如果切片ID已存在，则覆盖旧版本
     *
     * @param docs 待添加的切片列表
     */
    suspend fun addDocuments(docs: List<DocumentChunk>)

    /**
     * 添加单个文档切片（便捷方法）
     */
    suspend fun addDocument(chunk: DocumentChunk) {
        addDocuments(listOf(chunk))
    }

    /**
     * 向量搜索
     *
     * 先对 query 文本向量化（内部调用 embeddingProvider），
     * 然后计算与库中所有向量的余弦相似度，返回 topK 个最相似结果。
     *
     * @param query 查询文本
     * @param topK 返回的最大结果数，默认 5
     * @return 按相似度从高到低排序的结果列表
     */
    suspend fun search(query: String, topK: Int = 5): List<SearchResult>

    /**
     * 根据向量直接搜索（外部已向量化时使用）
     */
    suspend fun searchByVector(vector: FloatArray, topK: Int = 5): List<SearchResult>

    /**
     * 删除指定文档ID的所有切片
     *
     * @param docId 原始文档ID
     */
    suspend fun deleteDocument(docId: String)

    /**
     * 删除单个切片
     */
    suspend fun deleteChunk(chunkId: String)

    /**
     * 清空整个向量库
     */
    suspend fun clearAll()

    /**
     * 观察向量库统计信息的 Flow
     * 统计信息变更时自动推送（add/delete后）
     */
    fun observeStats(): Flow<VectorStoreStats>

    /**
     * 获取当前统计快照
     */
    suspend fun getStats(): VectorStoreStats

    /**
     * 查询某文档是否已索引
     */
    suspend fun hasDocument(docId: String): Boolean

    /**
     * 获取所有已索引的文档ID列表
     */
    suspend fun getAllDocumentIds(): List<String>
}

// ==================== 默认实现：内存向量库 ====================

/**
 * 内存向量库（基于余弦相似度的轻量级实现）
 *
 * 特点：
 * 1. 零第三方依赖，纯 Kotlin 实现
 * 2. 切片存储：ConcurrentHashMap<chunkId, DocumentChunk>
 * 3. 文档索引：Map<docId, Set<chunkId>> 便于按doc删除
 * 4. 搜索：遍历全部向量 + 余弦相似度，数据量 < 1万条时性能可接受
 * 5. 数据不持久化：应用重启后丢失（需要持久化请接入SQLite-VSS）
 *
 * 复杂度：
 * - 写入：O(n) 单条
 * - 搜索：O(N * D)，N=切片总数，D=向量维度
 *
 * TODO 未来优化：
 * - 引入 HNSW / FAISS 近似最近邻索引，降低搜索复杂度到 O(log N * D)
 * - 周期性快照写入本地文件，实现冷启动恢复
 */
@Singleton
class InMemoryVectorStore @Inject constructor(
    private val embeddingProvider: EmbeddingProvider
) : VectorStore {

    companion object {
        private const val TAG = "InMemoryVectorStore"
    }

    /** 切片存储：chunkId -> DocumentChunk */
    private val chunks = ConcurrentHashMap<String, DocumentChunk>()

    /** 文档到切片的反向索引：docId -> Set<chunkId> */
    private val docToChunks = ConcurrentHashMap<String, MutableSet<String>>()

    /** 写操作互斥锁：add / delete / clear 需要串行化 */
    private val writeMutex = Mutex()

    /** 统计信息 StateFlow */
    private val _statsFlow = MutableStateFlow(
        VectorStoreStats(0, 0, embeddingProvider.dimension, 0L)
    )

    override fun observeStats(): Flow<VectorStoreStats> = _statsFlow.asStateFlow()

    override suspend fun getStats(): VectorStoreStats = _statsFlow.value

    // ==================== 写入 ====================

    override suspend fun addDocuments(docs: List<DocumentChunk>) {
        if (docs.isEmpty()) return

        writeMutex.withLock {
            var addedOrUpdated = 0
            for (chunk in docs) {
                // 向量化兜底：如果调用方没有给 embedding，此处兜底生成
                val finalChunk = if (chunk.embedding.isEmpty()) {
                    val vec = embeddingProvider.embed(chunk.content)
                    chunk.copy(embedding = vec)
                } else chunk

                // 检查维度匹配
                if (finalChunk.embedding.size != embeddingProvider.dimension) {
                    Log.w(TAG, "切片 ${finalChunk.id} 向量维度不匹配，跳过" +
                            "（期望${embeddingProvider.dimension}，实际${finalChunk.embedding.size}）")
                    continue
                }

                chunks[finalChunk.id] = finalChunk
                docToChunks.getOrPut(finalChunk.docId) { mutableSetOf() }.add(finalChunk.id)
                addedOrUpdated++
            }
            Log.d(TAG, "向量库写入：新增/更新 $addedOrUpdated 条切片")
            refreshStats()
        }
    }

    // ==================== 搜索 ====================

    override suspend fun search(query: String, topK: Int): List<SearchResult> {
        val queryVector = embeddingProvider.embed(query)
        return searchByVector(queryVector, topK)
    }

    override suspend fun searchByVector(vector: FloatArray, topK: Int): List<SearchResult> {
        if (chunks.isEmpty() || vector.isEmpty()) return emptyList()

        val startTime = System.currentTimeMillis()

        // 遍历计算余弦相似度
        val results = ArrayList<SearchResult>(chunks.size)
        val chunkList = chunks.values.toList()
        for (chunk in chunkList) {
            val sim = cosineSimilarity(vector, chunk.embedding)
            if (!sim.isNaN()) {
                results += SearchResult(chunk, sim)
            }
        }

        // 按相似度降序排序，取topK
        results.sortByDescending { it.similarityScore }
        val top = results.take(topK)

        val cost = System.currentTimeMillis() - startTime
        Log.d(TAG, "向量搜索完成：total=${chunks.size}, topK=$topK, 耗时=${cost}ms, " +
                "topScore=${top.firstOrNull()?.similarityScore ?: 0f}")
        return top
    }

    // ==================== 删除 ====================

    override suspend fun deleteDocument(docId: String) {
        writeMutex.withLock {
            val chunkIds = docToChunks.remove(docId) ?: return@withLock
            for (cid in chunkIds) {
                chunks.remove(cid)
            }
            Log.d(TAG, "删除文档 $docId，共 ${chunkIds.size} 条切片")
            refreshStats()
        }
    }

    override suspend fun deleteChunk(chunkId: String) {
        writeMutex.withLock {
            val chunk = chunks.remove(chunkId) ?: return@withLock
            docToChunks[chunk.docId]?.remove(chunkId)
            if (docToChunks[chunk.docId].isNullOrEmpty()) {
                docToChunks.remove(chunk.docId)
            }
            refreshStats()
        }
    }

    override suspend fun clearAll() {
        writeMutex.withLock {
            chunks.clear()
            docToChunks.clear()
            Log.i(TAG, "向量库已清空")
            refreshStats()
        }
    }

    // ==================== 查询 ====================

    override suspend fun hasDocument(docId: String): Boolean = docToChunks.containsKey(docId)

    override suspend fun getAllDocumentIds(): List<String> = docToChunks.keys().toList()

    // ==================== 辅助方法 ====================

    /**
     * 刷新统计信息 Flow
     */
    private fun refreshStats() {
        val totalChunks = chunks.size
        val totalDocs = docToChunks.size
        val dim = embeddingProvider.dimension
        // 估算内存：每个Float=4字节 + 对象头（粗略估算）
        val sizeBytes = totalChunks.toLong() * (dim * 4L + 128L)
        _statsFlow.value = VectorStoreStats(totalChunks, totalDocs, dim, sizeBytes)
    }

    /**
     * 计算两个向量的余弦相似度
     * 返回值：-1.0（完全相反）~ 1.0（完全相同），0.0表示正交
     *
     * 公式：cos(a,b) = Σ(a_i * b_i) / (||a|| * ||b||)
     */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return Float.NaN
        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            val ai = a[i].toDouble()
            val bi = b[i].toDouble()
            dotProduct += ai * bi
            normA += ai * ai
            normB += bi * bi
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0.0) 0f else (dotProduct / denom).toFloat()
    }
}
