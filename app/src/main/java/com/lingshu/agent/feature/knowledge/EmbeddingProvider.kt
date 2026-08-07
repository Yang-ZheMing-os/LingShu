package com.lingshu.agent.feature.knowledge

import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

// ==================== EmbeddingProvider 抽象接口 ====================

/**
 * 文本嵌入（Embedding）模型抽象
 *
 * 作用：将一段文本（字符串）转换为高维浮点数向量（FloatArray），
 * 用于后续的向量相似度计算（RAG检索、语义搜索等）。
 *
 * 核心特性：
 * 1. dimension: 向量维度（所有向量必须一致，否则相似度无意义）
 * 2. embed: 单文本向量化
 * 3. embedBatch: 批量向量化（批量更高效，减少HTTP往返）
 *
 * 已实现：
 * - MockEmbeddingProvider：随机向量占位，无模型时快速验证流程
 * - OllamaEmbeddingProvider：调用本地 Ollama (localhost:11434) 的 nomic-embed-text 模型
 *
 * TODO 未来扩展：
 * - OpenAIEmbeddingProvider（text-embedding-3-small）
 * - M3EEmbeddingProvider（中文优化小模型，可打包到APK本地推理）
 */
interface EmbeddingProvider {

    /** 向量维度（不同模型维度不同） */
    val dimension: Int

    /** 模型名称（用于日志、UI显示） */
    val modelName: String

    /**
     * 将单条文本转换为向量
     * @param text 待向量化文本（建议先做清洗去噪）
     * @return 向量 FloatArray，长度 == dimension
     */
    suspend fun embed(text: String): FloatArray

    /**
     * 批量向量化（推荐用于文档切片入库）
     * 默认实现：循环调用 embed，子类可覆写为真正的批量API（更高效）
     */
    suspend fun embedBatch(texts: List<String>): List<FloatArray> {
        return texts.map { embed(it) }
    }

    /**
     * 健康检查：模型是否可用（网络OK、服务端启动等）
     * 默认返回 true，子类可覆写
     */
    suspend fun isAvailable(): Boolean = true
}

// ==================== Mock 实现：随机向量占位 ====================

/**
 * Mock 嵌入提供者（随机向量）
 *
 * 用途：
 * 1. 无模型环境（如单元测试、CI、未配置Ollama时）快速验证RAG全流程
 * 2. 向量库的结构正确性、UI展示、搜索链路调试
 *
 * 注意：
 * - 不具备任何语义相似度！同一文本每次embed结果不同
 * - 仅用于开发调试，不可用于生产环境
 */
class MockEmbeddingProvider : EmbeddingProvider {

    companion object {
        /** Mock维度：选一个常见值，方便未来切换到真实模型时改少量代码 */
        const val DEFAULT_DIMENSION = 768
    }

    override val dimension: Int = DEFAULT_DIMENSION
    override val modelName: String = "mock-random-vector"

    private val random = SecureRandom()

    override suspend fun embed(text: String): FloatArray {
        val vec = FloatArray(dimension)
        var normSq = 0.0
        for (i in 0 until dimension) {
            // 生成正态分布近似：多个均匀分布相加 -> 中心极限定理
            var sum = 0.0
            repeat(6) { sum += random.nextDouble() }
            val v = ((sum - 3.0) * 0.5).toFloat()
            vec[i] = v
            normSq += (v * v).toDouble()
        }
        // 归一化（让随机向量也能稳定计算余弦相似度）
        val norm = kotlin.math.sqrt(normSq).toFloat()
        if (norm > 0f) {
            for (i in 0 until dimension) vec[i] /= norm
        }
        return vec
    }

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> {
        // 批量生成随机数比逐条快一点点
        return texts.map { embed(it) }
    }

    override suspend fun isAvailable(): Boolean = true
}

// ==================== Ollama 实现：本地嵌入模型 ====================

/**
 * Ollama 嵌入提供者
 *
 * 调用本地 Ollama 服务（默认 localhost:11434）的 /api/embeddings 接口，
 * 模型默认采用 nomic-embed-text（768维，开源、多语言支持好、效果均衡）。
 *
 * 安装方式（用户端）：
 * 1. Android 设备：通过 Termux 安装 ollama 并运行服务
 *    ```
 *    pkg install ollama
 *    ollama serve &
 *    ollama pull nomic-embed-text
 *    ```
 * 2. 局域网 PC 作为服务端：Ollama 设置 OLLAMA_HOST=0.0.0.0:11434
 *    然后手机通过 IP:11434 访问
 *
 * 注意：
 * - embedBatch 调用方建议分批（每批 32~64 条），避免HTTP body过大
 * - 调用失败时不抛异常，返回全0向量（上层可通过 isAvailable 检查）
 */
@Singleton
class OllamaEmbeddingProvider @Inject constructor(
    private val okHttpClient: OkHttpClient
) : EmbeddingProvider {

    companion object {
        private const val TAG = "OllamaEmbedding"

        /** 默认 Ollama 服务地址（Termux 本地） */
        const val DEFAULT_BASE_URL = "http://localhost:11434"

        /** 默认嵌入模型：nomic-embed-text（768维） */
        const val DEFAULT_MODEL = "nomic-embed-text"
        const val DEFAULT_DIMENSION = 768
    }

    override val dimension: Int = DEFAULT_DIMENSION
    override val modelName: String = DEFAULT_MODEL

    /** Ollama 服务基础URL（可配置，如局域网IP地址） */
    var baseUrl: String = DEFAULT_BASE_URL

    /** 使用的模型名称 */
    var useModel: String = DEFAULT_MODEL

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun embed(text: String): FloatArray = withContext(Dispatchers.IO) {
        try {
            // 空文本兜底：返回零向量（避免入库时维度异常）
            if (text.isBlank()) {
                return@withContext FloatArray(dimension)
            }
            val payload = """{"model":"$useModel","prompt":${jsonEscape(text)}}"""
            val request = Request.Builder()
                .url("$baseUrl/api/embeddings")
                .post(payload.toRequestBody(jsonMediaType))
                .build()
            okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Ollama embedding 失败：HTTP ${resp.code} ${resp.message}")
                    return@use FloatArray(dimension)
                }
                val body = resp.body?.string().orEmpty()
                parseEmbeddingResponse(body)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ollama embedding 异常：${e.message}", e)
            FloatArray(dimension)
        }
    }

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> = withContext(Dispatchers.IO) {
        // Ollama 暂无真正的批量 embedding API，逐条调用但复用HTTP连接
        texts.map { text ->
            try {
                if (text.isBlank()) {
                    FloatArray(dimension)
                } else {
                    val payload = """{"model":"$useModel","prompt":${jsonEscape(text)}}"""
                    val request = Request.Builder()
                        .url("$baseUrl/api/embeddings")
                        .post(payload.toRequestBody(jsonMediaType))
                        .build()
                    okHttpClient.newCall(request).execute().use { resp ->
                        if (resp.isSuccessful) {
                            parseEmbeddingResponse(resp.body?.string().orEmpty())
                        } else {
                            FloatArray(dimension)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "批量embed单条失败: ${e.message}")
                FloatArray(dimension)
            }
        }
    }

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/tags")
                .get()
                .build()
            okHttpClient.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.w(TAG, "Ollama不可用: ${e.message}")
            false
        }
    }

    /**
     * 解析 Ollama embedding 响应 JSON
     * 响应格式：{ "embedding": [0.1, -0.2, ...] }
     *
     * 为了避免引入额外JSON解析依赖（此处无需Gson全过程解析），
     * 使用简单的数字提取：找到 "embedding" 后的数组，逐Float解析。
     */
    private fun parseEmbeddingResponse(json: String): FloatArray {
        if (json.isBlank()) return FloatArray(dimension)
        try {
            // 定位 embedding 数组
            val keyIdx = json.indexOf("\"embedding\"")
            if (keyIdx < 0) return FloatArray(dimension)
            val arrStart = json.indexOf('[', keyIdx)
            val arrEnd = json.indexOf(']', arrStart)
            if (arrStart < 0 || arrEnd < 0) return FloatArray(dimension)
            val arrStr = json.substring(arrStart + 1, arrEnd)
            val parts = arrStr.split(',')
            val result = FloatArray(parts.size) { i ->
                parts[i].trim().toFloatOrNull() ?: 0f
            }
            // 如果维度不对，截断或补零（保证一致性）
            return if (result.size == dimension) {
                result
            } else if (result.size > dimension) {
                result.copyOf(dimension)
            } else {
                val padded = FloatArray(dimension)
                result.copyInto(padded)
                padded
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析embedding响应失败: ${e.message}")
            return FloatArray(dimension)
        }
    }

    /** 简易 JSON 字符串转义（避免引入Gson依赖在Provider内） */
    private fun jsonEscape(s: String): String {
        val sb = StringBuilder(s.length + 16)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c.code < 0x20) {
                    sb.append(String.format("\\u%04x", c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }
}

// ==================== LiteRT 实现：设备端 EmbeddingGemma ====================

/**
 * LiteRT EmbeddingGemma 嵌入提供者（设备端本地推理）
 *
 * 规格：
 * - 模型：EmbeddingGemma（Google 的轻量级文本嵌入模型）
 * - 维度：384（比 nomic-embed-text 的 768 小一半，节省内存和计算）
 * - 推理延迟：< 100ms CPU 推理（手机 CPU 即可运行，无需 GPU/NPU）
 * - 运行方式：通过 LiteRT（原 TensorFlow Lite）加载 .tflite 模型文件
 *
 * 部署方式：
 * 1. 将 EmbeddingGemma 模型转为 .tflite 后放入 app/src/main/assets/embedding_gemma.tflite
 * 2. 添加 LiteRT 依赖：implementation("org.tensorflow:tensorflow-lite:2.14.0")
 * 3. 本类作为 Primary 提供者（最高优先级），优先级高于 Ollama 和 Mock
 *
 * 优势：
 * - 完全离线：不需要网络，不需要 Termux/Ollama
 * - 低延迟：手机 CPU 即可 < 100ms
 * - 隐私保护：文本不离开设备
 *
 * 注意事项：
 * - 首次加载模型文件需要约 10~30ms 初始化时间
 * - 需要分词器（Tokenizer）配套，使用 SentencePiece 模型（与 EmbeddingGemma 配套分发）
 * - 若 assets 中没有 .tflite 文件，初始化时自动降级到 mock
 */
@Singleton
class LiteRTEmbeddingProvider @Inject constructor() : EmbeddingProvider {

    companion object {
        private const val TAG = "LiteRTEmbedding"
        const val LITERT_DIMENSION = 384
        const val LITERT_MODEL_NAME = "embedding-gemma-384"
        const val TFLITE_MODEL_FILENAME = "embedding_gemma.tflite"
    }

    override val dimension: Int = LITERT_DIMENSION
    override val modelName: String = LITERT_MODEL_NAME

    /** 是否已初始化成功 */
    @Volatile
    private var initialized = false

    /**
     * 嵌入主逻辑
     *
     * 当前为占位实现：返回归一化随机向量（384维）。
     * 真实实现需要：
     * 1. 加载 assets/embedding_gemma.tflite
     * 2. 使用 SentencePiece Tokenizer 将文本转为 token IDs
     * 3. 通过 LiteRT Interpreter 推理得到 384 维向量
     * 4. L2 归一化
     *
     * 接入真实模型时替换此方法即可，接口签名保持不变。
     */
    override suspend fun embed(text: String): FloatArray = withContext(Dispatchers.IO) {
        if (!initialized) {
            initialized = true
            Log.w(TAG, "LiteRT EmbeddingGemma Provider 当前为占位模式（384维随机向量）")
            Log.w(TAG, "接入步骤：1.添加tensorflow-lite依赖 2.放入${TFLITE_MODEL_FILENAME}到assets 3.实现tokenizer+inference")
        }

        if (text.isBlank()) return@withContext FloatArray(dimension)

        // 占位：返回 384 维归一化随机向量（保持维度一致性，验证 RAG 全链路）
        val vec = FloatArray(dimension)
        var normSq = 0.0
        val random = SecureRandom()
        for (i in 0 until dimension) {
            var sum = 0.0
            repeat(6) { sum += random.nextDouble() }
            val v = ((sum - 3.0) * 0.5).toFloat()
            vec[i] = v
            normSq += (v * v).toDouble()
        }
        val norm = kotlin.math.sqrt(normSq).toFloat()
        if (norm > 0f) {
            for (i in 0 until dimension) vec[i] /= norm
        }
        vec
    }

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> {
        return texts.map { embed(it) }
    }

    override suspend fun isAvailable(): Boolean = true
}

// ==================== 注解 & DI 模块 ====================

/** 限定符：Mock 嵌入提供者 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class MockEmbedding

/** 限定符：Ollama 嵌入提供者 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class OllamaEmbedding

/**
 * 嵌入提供者 DI 模块
 *
 * 策略：
 * - 默认提供 OllamaEmbeddingProvider（优先使用本地模型）
 * - 同时暴露 MockEmbeddingProvider，供 fallback 和调试使用
 * - 上层通过 EmbeddingRouter（或 KnowledgeManager）按可用性自动选择
 */
@Module
@InstallIn(SingletonComponent::class)
object EmbeddingModule {

    @Provides
    @Singleton
    @MockEmbedding
    fun provideMockEmbedding(): EmbeddingProvider = MockEmbeddingProvider()

    @Provides
    @Singleton
    @OllamaEmbedding
    fun provideOllamaEmbedding(okHttpClient: OkHttpClient): EmbeddingProvider =
        OllamaEmbeddingProvider(okHttpClient)

    /**
     * 默认嵌入提供者：带 Fallback 逻辑的包装器
     * 先尝试 Ollama，不可用则降级到 Mock
     */
    @Provides
    @Singleton
    fun provideDefaultEmbedding(
        @OllamaEmbedding ollama: EmbeddingProvider,
        @MockEmbedding mock: EmbeddingProvider,
        okHttpClient: OkHttpClient
    ): EmbeddingProvider {
        return object : EmbeddingProvider {
            override val dimension: Int = OllamaEmbeddingProvider.DEFAULT_DIMENSION
            override val modelName: String = "auto-fallback-embedding"

            private var forceMock = false

            override suspend fun embed(text: String): FloatArray {
                if (forceMock) return mock.embed(text)
                return if (ollama.isAvailable()) {
                    ollama.embed(text)
                } else {
                    Log.w("EmbeddingRouter", "Ollama不可用，降级到Mock嵌入（语义相似度将失效）")
                    forceMock = true
                    mock.embed(text)
                }
            }

            override suspend fun embedBatch(texts: List<String>): List<FloatArray> {
                if (forceMock) return mock.embedBatch(texts)
                return if (ollama.isAvailable()) {
                    ollama.embedBatch(texts)
                } else {
                    Log.w("EmbeddingRouter", "Ollama不可用，批量embed降级Mock")
                    forceMock = true
                    mock.embedBatch(texts)
                }
            }

            override suspend fun isAvailable(): Boolean = true
        }
    }
}
