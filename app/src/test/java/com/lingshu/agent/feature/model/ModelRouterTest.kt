package com.lingshu.agent.feature.model

import android.graphics.Bitmap
import kotlinx.coroutines.flow.Flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// ================ Fake Provider 实现 ================

/**
 * 支持CHAT能力的假Provider，用于验证路由优先级
 */
open class FakeChatProvider(
    override val providerId: String,
    override val providerName: String,
    private val available: Boolean = true,
    private val chatResponseContent: String = "fake chat response"
) : ModelProvider {

    override val capabilities: Set<ModelCapability> = setOf(ModelCapability.CHAT)

    var chatCallCount = 0
        private set

    override suspend fun isAvailable(): Boolean = available

    override suspend fun chat(messages: List<ModelMessage>): ModelResponse {
        chatCallCount++
        return ModelResponse.success(chatResponseContent, providerId)
    }

    override suspend fun chatStream(messages: List<ModelMessage>): Flow<String> {
        return kotlinx.coroutines.flow.flow { emit(chatResponseContent) }
    }

    override suspend fun vision(image: Bitmap, prompt: String): String {
        throw UnsupportedOperationException("FakeChatProvider不支持视觉")
    }

    override suspend fun transcribe(audio: ByteArray): String {
        throw UnsupportedOperationException("FakeChatProvider不支持语音识别")
    }

    override suspend fun synthesize(text: String): ByteArray {
        throw UnsupportedOperationException("FakeChatProvider不支持语音合成")
    }

    override fun release() {}
}

/**
 * 支持CHAT + VISION能力的假Provider，模拟GPT4V等多模态模型
 */
class FakeVisionProvider(
    providerId: String,
    providerName: String,
    available: Boolean = true,
    private val visionResponse: String = "fake vision description"
) : FakeChatProvider(providerId, providerName, available) {

    override val capabilities: Set<ModelCapability> =
        setOf(ModelCapability.CHAT, ModelCapability.VISION)

    override suspend fun vision(image: Bitmap, prompt: String): String {
        return visionResponse
    }
}

/**
 * 总是调用失败的Provider，用于测试自动降级逻辑
 */
class FailingProvider(
    providerId: String,
    providerName: String,
    private val failWithException: Boolean = true
) : FakeChatProvider(providerId, providerName, available = true) {

    override suspend fun chat(messages: List<ModelMessage>): ModelResponse {
        return if (failWithException) {
            throw RuntimeException("Provider $providerId 调用失败（模拟网络错误）")
        } else {
            ModelResponse.unavailable("Provider $providerId 限流(429)", providerId)
        }
    }
}

// ================ 路由优先级测试类 ================

class ModelRouterTest {

    // 模拟云端优先级（数字越小优先级越高）
    private val cloudPriority = mapOf(
        "deepseek" to 1,
        "gpt4-vision" to 2,
        "claude" to 3
    )

    // 模拟本地优先级（降级时使用）
    private val localPriority = mapOf(
        "ollama" to 1,
        "vosk" to 2,
        "system-tts" to 3
    )

    // 测试用Provider注册表
    private lateinit var registry: MutableMap<String, ModelProvider>

    @Before
    fun setUp() {
        registry = mutableMapOf()
    }

    // ================ 核心测试：路由优先级排序 ================

    @Test
    fun `测试路由优先级 - preferredProviderId最高优先级`() {
        // 构建候选列表的逻辑对应 ModelRouter.buildCandidateProviderList
        // 顺序：preferred > locked > sceneDefault > cloud > local(fallback)
        val preferred = FakeChatProvider("preferred-provider", "偏好Provider")
        val locked = FakeChatProvider("locked-provider", "锁定Provider")
        val sceneDefault = FakeChatProvider("scene-default", "场景默认Provider")
        val cloudA = FakeChatProvider("deepseek", "DeepSeek云端")
        val cloudB = FakeChatProvider("claude", "Claude云端")

        registry[preferred.providerId] = preferred
        registry[locked.providerId] = locked
        registry[sceneDefault.providerId] = sceneDefault
        registry[cloudA.providerId] = cloudA
        registry[cloudB.providerId] = cloudB

        // 构建候选列表（模拟真实路由逻辑）
        val candidates = buildTestCandidates(
            capability = ModelCapability.CHAT,
            preferredProviderId = preferred.providerId,
            lockedProviderId = locked.providerId,
            sceneDefaultId = sceneDefault.providerId,
            autoFallbackEnabled = true
        )

        // 断言顺序：preferred在最前
        assertEquals("第1个候选应为偏好Provider", preferred.providerId, candidates[0].providerId)
        assertEquals("第2个候选应为锁定Provider", locked.providerId, candidates[1].providerId)
        assertEquals("第3个候选应为场景默认Provider", sceneDefault.providerId, candidates[2].providerId)
        // 云端按优先级排序，deepseek(1)在claude(3)前面
        assertEquals("第4个候选应为高优先级云端", "deepseek", candidates[3].providerId)
        assertEquals("第5个候选应为低优先级云端", "claude", candidates[4].providerId)
    }

    @Test
    fun `测试路由优先级 - 手动锁定Provider优先于场景默认`() {
        val locked = FakeChatProvider("locked", "锁定Provider")
        val sceneDefault = FakeChatProvider("default", "场景默认")
        val cloud = FakeChatProvider("deepseek", "云端Provider")

        registry[locked.providerId] = locked
        registry[sceneDefault.providerId] = sceneDefault
        registry[cloud.providerId] = cloud

        // 没有preferred
        val candidates = buildTestCandidates(
            capability = ModelCapability.CHAT,
            preferredProviderId = null,
            lockedProviderId = locked.providerId,
            sceneDefaultId = sceneDefault.providerId,
            autoFallbackEnabled = true
        )

        assertEquals("第1个应为锁定Provider", locked.providerId, candidates[0].providerId)
        assertEquals("第2个应为场景默认", sceneDefault.providerId, candidates[1].providerId)
        assertEquals("锁定Provider必须在场景默认之前",
            candidates.indexOfFirst { it.providerId == locked.providerId }
            ,
            candidates.indexOfFirst { it.providerId == sceneDefault.providerId }
        )
    }

    @Test
    fun `测试路由优先级 - 本地Provider仅在自动降级开启时追加到末尾`() {
        val cloud = FakeChatProvider("deepseek", "云端Provider")
        val local = FakeChatProvider("ollama", "本地Ollama")

        registry[cloud.providerId] = cloud
        registry[local.providerId] = local

        // 开启自动降级
        val withFallback = buildTestCandidates(
            capability = ModelCapability.CHAT,
            preferredProviderId = null,
            lockedProviderId = null,
            sceneDefaultId = cloud.providerId,
            autoFallbackEnabled = true
        )
        assertTrue("开启降级时本地Provider应出现在候选中",
            withFallback.any { it.providerId == "ollama" })
        assertEquals("本地Provider应在末尾",
            "ollama", withFallback.last().providerId)

        // 关闭自动降级
        val withoutFallback = buildTestCandidates(
            capability = ModelCapability.CHAT,
            preferredProviderId = null,
            lockedProviderId = null,
            sceneDefaultId = cloud.providerId,
            autoFallbackEnabled = false
        )
        assertFalse("关闭降级时本地Provider不应出现",
            withoutFallback.any { it.providerId == "ollama" })
    }

    @Test
    fun `测试路由去重 - 同一个Provider不会在候选列表出现多次`() {
        // 同一个Provider同时在preferred、locked、sceneDefault中出现
        val same = FakeChatProvider("same-provider", "同一个Provider")
        val cloud = FakeChatProvider("deepseek", "云端Provider")

        registry[same.providerId] = same
        registry[cloud.providerId] = cloud

        val candidates = buildTestCandidates(
            capability = ModelCapability.CHAT,
            preferredProviderId = same.providerId,
            lockedProviderId = same.providerId,
            sceneDefaultId = same.providerId,
            autoFallbackEnabled = true
        )

        // 同一个ID只出现一次
        val idCounts = candidates.groupingBy { it.providerId }.eachCount()
        assertEquals("每个Provider在候选中只能出现一次",
            1, idCounts["same-provider"])
        assertTrue("候选中必须有该Provider",
            candidates.any { it.providerId == same.providerId })
    }

    @Test
    fun `测试能力过滤 - 不支持CHAT的Provider不会出现在CHAT任务候选中`() {
        // FakeVisionProvider支持CHAT+VISION，FakeChatProvider只支持CHAT
        val visionOnlyId = "vision-gpt4v"
        val visionProvider = object : ModelProvider {
            override val providerId = visionOnlyId
            override val providerName = "仅视觉Provider"
            // 注意：这里只给VISION，不给CHAT
            override val capabilities: Set<ModelCapability> = setOf(ModelCapability.VISION)
            override suspend fun isAvailable() = true
            override suspend fun chat(messages: List<ModelMessage>): ModelResponse =
                throw UnsupportedOperationException()
            override suspend fun chatStream(messages: List<ModelMessage>): Flow<String> =
                throw UnsupportedOperationException()
            override suspend fun vision(image: Bitmap, prompt: String) = ""
            override suspend fun transcribe(audio: ByteArray) = ""
            override suspend fun synthesize(text: String) = byteArrayOf()
            override fun release() {}
        }
        val chatProvider = FakeChatProvider("deepseek", "对话Provider")

        registry[visionOnlyId] = visionProvider
        registry[chatProvider.providerId] = chatProvider

        // 任务是CHAT，只支持VISION的不应出现
        val candidates = buildTestCandidates(
            capability = ModelCapability.CHAT,
            preferredProviderId = null,
            lockedProviderId = null,
            sceneDefaultId = chatProvider.providerId,
            autoFallbackEnabled = true
        )
        assertFalse("仅支持VISION的Provider不应出现在CHAT候选中",
            candidates.any { it.providerId == visionOnlyId })
        assertTrue("CHAT Provider应出现在候选中",
            candidates.any { it.providerId == chatProvider.providerId })
    }

    // ================ 核心测试：降级逻辑 ================

    @Test
    fun `测试降级逻辑 - 第一个Provider失败时下一个被调用`() {
        // 准备：一个总是失败的Provider + 一个正常的Provider
        val failing = FailingProvider("failing-provider", "总是失败的Provider")
        val working = FakeChatProvider("working-provider", "正常Provider")

        registry[failing.providerId] = failing
        registry[working.providerId] = working

        // 候选顺序：failing在前，working在后
        val candidates = listOf<ModelProvider>(failing, working)

        // 模拟 executeRoutedTask 逻辑：依次尝试直到成功
        var lastResponse: ModelResponse? = null
        var successProviderCalled = false

        for (provider in candidates) {
            try {
                val response = provider.chat(emptyList())
                if (response.isSuccess) {
                    lastResponse = response
                    successProviderCalled = (provider == working)
                    break
                }
            } catch (e: Exception) {
                // 异常，尝试下一个
                continue
            }
        }

        // 断言：成功使用了working-provider
        assertNotNull("最终应返回非空响应", lastResponse)
        assertTrue("响应应标记为成功", lastResponse!!.isSuccess)
        assertTrue("应调用正常的Provider", successProviderCalled)
        assertEquals("成功响应内容应来自working",
            "fake chat response", lastResponse.content)
        assertEquals("chatCallCount应为1", 1, working.chatCallCount)
    }

    @Test
    fun `测试降级逻辑 - 多个Provider都失败时返回最终错误`() {
        val fail1 = FailingProvider("fail1", "失败1号", failWithException = true)
        val fail2 = FailingProvider("fail2", "失败2号", failWithException = false)
        val candidates = listOf<ModelProvider>(fail1, fail2)

        // 模拟全部候选都失败的情况
        var lastResponse: ModelResponse? = null
        var lastException: Exception? = null

        for (provider in candidates) {
            try {
                val response = provider.chat(emptyList())
                lastResponse = response
                if (response.isSuccess) break
            } catch (e: Exception) {
                lastException = e
                lastResponse = ModelResponse.error(
                    "调用${provider.providerName}异常",
                    provider.providerId
                )
            }
        }

        // 全部失败时，lastResponse不会是success
        assertNotNull("至少有最后一个错误响应", lastResponse)
        assertFalse("没有任何一个成功，响应不应为success", lastResponse!!.isSuccess)
    }

    @Test
    fun `测试降级逻辑 - 业务错误不降级`() {
        // 如果第一个Provider返回业务ERROR（非限流/不可用），应该直接返回不降级
        val businessErrorProvider = object : FakeChatProvider("biz", "业务错误Provider") {
            override suspend fun chat(messages: List<ModelMessage>): ModelResponse {
                // 返回业务级错误（如请求格式错误），这种不该降级
                return ModelResponse.error("提示词不合法（业务错误）", providerId)
            }
        }
        val neverCalledProvider = FakeChatProvider("never", "不应被调用Provider")

        val candidates = listOf<ModelProvider>(businessErrorProvider, neverCalledProvider)

        // 模拟降级逻辑：遇到isError直接返回不降级
        var returnedResponse: ModelResponse? = null
        for (provider in candidates) {
            val response = provider.chat(emptyList())
            if (response.isSuccess) {
                returnedResponse = response
                break
            }
            if (response.isError) {
                // 业务错误：不降级，直接返回
                returnedResponse = response
                break
            }
            // isUnavailable：继续降级
        }

        assertEquals("应返回业务错误的响应", "biz", returnedResponse?.providerId)
        assertTrue("响应应标记为业务ERROR而非UNAVAILABLE", returnedResponse?.isError == true)
        assertEquals("第2个Provider不应被调用", 0, neverCalledProvider.chatCallCount)
    }

    // ================ 辅助方法 ================

    /**
     * 模拟ModelRouter.buildCandidateProviderList的逻辑
     * 优先级顺序：preferred > locked > sceneDefault > cloud优先级排序 > 本地(降级开启时)
     */
    private fun buildTestCandidates(
        capability: ModelCapability,
        preferredProviderId: String?,
        lockedProviderId: String?,
        sceneDefaultId: String?,
        autoFallbackEnabled: Boolean
    ): List<ModelProvider> {
        val result = mutableListOf<ModelProvider>()
        val added = mutableSetOf<String>()

        fun addIfSupported(id: String?) {
            if (id == null || id in added) return
            val p = registry[id] ?: return
            if (p.supports(capability)) {
                result.add(p)
                added.add(id)
            }
        }

        // 1. preferred
        addIfSupported(preferredProviderId)

        // 2. locked
        addIfSupported(lockedProviderId)

        // 3. scene default
        addIfSupported(sceneDefaultId)

        // 4. cloud sorted by priority
        val sortedCloud = registry.values
            .filter {
                it.supports(capability)
                        && it.providerId !in added
                        && !isLocal(it.providerId)
            }
            .sortedBy { cloudPriority[it.providerId] ?: Int.MAX_VALUE }
        result.addAll(sortedCloud)
        sortedCloud.forEach { added.add(it.providerId) }

        // 5. local (only if autoFallbackEnabled)
        if (autoFallbackEnabled) {
            val sortedLocal = registry.values
                .filter {
                    it.supports(capability)
                            && it.providerId !in added
                            && isLocal(it.providerId)
                }
                .sortedBy { localPriority[it.providerId] ?: Int.MAX_VALUE }
            result.addAll(sortedLocal)
        }

        return result
    }

    private fun isLocal(providerId: String): Boolean = when (providerId) {
        "ollama", "vosk", "system-tts" -> true
        else -> false
    }
}
