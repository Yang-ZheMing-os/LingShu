package com.lingshu.feature.rag.data

import com.lingshu.core.common.error.ErrorCodes
import com.lingshu.core.common.error.Result
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.core.data.llm.ChatMessage
import com.lingshu.core.data.llm.LlmConfig
import com.lingshu.core.data.llm.LlmRouter
import com.lingshu.core.data.llm.ModelProviderType
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 基于 Ollama 的 Embedding 引擎。
 *
 * 通过 LlmRouter.embeddings() 调用 Ollama 的 /api/embeddings 接口，
 * 使用 nomic-embed-text / bge-m3 等嵌入模型生成向量。
 *
 * 当 Ollama 不可用时，自动降级到 HashBasedEmbeddingEngine（基于文本特征哈希）。
 *
 * 维度说明：
 * - nomic-embed-text: 768 维
 * - bge-m3: 1024 维
 * - HashBasedEmbeddingEngine (fallback): 384 维
 */
@Singleton
class OllamaEmbeddingEngine @Inject constructor(
    private val llmRouter: LlmRouter,
    private val fallbackEngine: HashBasedEmbeddingEngine
) : IEmbeddingEngine {

    private val moduleTag = "OllamaEmbedding"

    companion object {
        /** Ollama 默认嵌入模型 */
        private const val DEFAULT_EMBED_MODEL = "nomic-embed-text"
        /** Ollama 默认地址（模拟器访问宿主机） */
        private const val DEFAULT_OLLAMA_URL = "http://10.0.2.2:11434"
    }

    @Volatile
    private var lastDimension: Int = 0

    override suspend fun embed(text: String): FloatArray {
        if (text.isBlank()) return FloatArray(0)

        val embedConfig = LlmConfig(
            provider = ModelProviderType.OLLAMA,
            baseUrl = DEFAULT_OLLAMA_URL,
            modelName = DEFAULT_EMBED_MODEL,
            temperature = 0.0f,
            maxTokens = 0,
            timeoutSeconds = 60
        )

        val traceId = "rag_embed_${System.currentTimeMillis()}"
        LingShuLog.d(moduleTag, "[$traceId] embed start | textLen=${text.length} | model=$DEFAULT_EMBED_MODEL")

        val result = llmRouter.embeddings(listOf(text), embedConfig, traceId = traceId)

        return when (result) {
            is Result.Success -> {
                val vectors = result.data
                if (vectors.isNotEmpty()) {
                    val vec = vectors.first()
                    lastDimension = vec.size
                    LingShuLog.d(moduleTag, "[$traceId] embed success | dim=${vec.size}")
                    FloatArray(vec.size) { vec[it] }
                } else {
                    LingShuLog.w(moduleTag, "[$traceId] Ollama 返回空向量，fallback 到 HashBased")
                    fallbackEngine.embed(text)
                }
            }
            is Result.Error -> {
                LingShuLog.w(moduleTag, "[$traceId] Ollama embedding 失败: ${result.code} | fallback 到 HashBased")
                fallbackEngine.embed(text)
            }
        }
    }

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()

        val embedConfig = LlmConfig(
            provider = ModelProviderType.OLLAMA,
            baseUrl = DEFAULT_OLLAMA_URL,
            modelName = DEFAULT_EMBED_MODEL,
            temperature = 0.0f,
            maxTokens = 0,
            timeoutSeconds = 60
        )

        val traceId = "rag_embed_batch_${System.currentTimeMillis()}"
        LingShuLog.i(moduleTag, "[$traceId] embedBatch start | count=${texts.size} | model=$DEFAULT_EMBED_MODEL")

        val result = llmRouter.embeddings(texts, embedConfig, traceId = traceId)

        return when (result) {
            is Result.Success -> {
                val vectors = result.data
                if (vectors.size == texts.size) {
                    lastDimension = vectors.firstOrNull()?.size ?: 0
                    LingShuLog.i(moduleTag, "[$traceId] embedBatch success | count=${vectors.size} | dim=$lastDimension")
                    vectors.map { vec -> FloatArray(vec.size) { vec[it] } }
                } else {
                    LingShuLog.w(moduleTag, "[$traceId] 维度不匹配: expected=${texts.size} got=${vectors.size} | fallback")
                    fallbackEngine.embedBatch(texts)
                }
            }
            is Result.Error -> {
                LingShuLog.w(moduleTag, "[$traceId] Ollama batch embedding 失败: ${result.code} | fallback 到 HashBased")
                fallbackEngine.embedBatch(texts)
            }
        }
    }

    /** 获取上次成功嵌入的维度（用于向量存储初始化） */
    fun lastDimension(): Int = lastDimension
}
