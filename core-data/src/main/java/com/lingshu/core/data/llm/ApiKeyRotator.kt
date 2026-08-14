package com.lingshu.core.data.llm

import com.lingshu.core.common.log.LingShuLog
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * API Key 轮询器：管理多 Key 轮换与失败冷却。
 *
 * - 同一 provider 的多个 Key 依次轮换使用
 * - 某个 Key 触发 429（限流）/ 401（鉴权失败）时，标记冷却（默认 60 秒）
 * - 冷却期内的 Key 会被跳过，优先使用未冷却的 Key
 * - 所有 Key 都在冷却时，返回冷却时间最短的那个（best-effort）
 *
 * 线程安全：内部使用 ConcurrentHashMap。
 */
@Singleton
class ApiKeyRotator @Inject constructor() {

    private val moduleTag = "ApiKeyRotator"

    companion object {
        /** 默认冷却时长（毫秒） */
        private const val DEFAULT_COOLDOWN_MS = 60_000L
        /** 最大冷却时长（指数退避上限） */
        private const val MAX_COOLDOWN_MS = 30 * 60_000L
    }

    /**
     * 每个 Key 的冷却状态。
     * @param cooldownUntil 冷却到此时间戳（System.currentTimeMillis）后可用
     * @param consecutiveFailures 连续失败次数（用于指数退避）
     */
    private data class KeyState(
        var cooldownUntil: Long = 0L,
        var consecutiveFailures: Int = 0
    )

    /** 按 "provider:apiKey" 维度存储 Key 状态 */
    private val keyStates = ConcurrentHashMap<String, KeyState>()

    private fun stateKey(provider: ModelProviderType, apiKey: String): String {
        return "${provider.name}::${apiKey.takeLast(8)}"
    }

    /**
     * 从 config 中选出当前可用的 Key。
     * 优先返回未冷却的主 Key；若主 Key 冷却，则尝试备用 Key；若全部冷却，返回冷却时间最短的 Key。
     *
     * @return 选中的 Key，若 config 没有任何 Key 则返回空字符串
     */
    fun pickAvailableKey(config: LlmConfig): String {
        val allKeys = config.allKeys()
        if (allKeys.isEmpty()) {
            LingShuLog.w(moduleTag, "pickAvailableKey[${config.provider}]: 无可用 Key")
            return ""
        }

        val now = System.currentTimeMillis()
        var bestOnCooldown: Pair<String, Long>? = null

        for (key in allKeys) {
            val sk = stateKey(config.provider, key)
            val state = keyStates[sk]
            if (state == null || state.cooldownUntil <= now) {
                if (state != null && state.cooldownUntil <= now && state.consecutiveFailures > 0) {
                    LingShuLog.d(moduleTag, "pickAvailableKey[${config.provider}]: Key ...${key.takeLast(8)} 冷却已过，重新启用")
                }
                LingShuLog.d(moduleTag, "pickAvailableKey[${config.provider}]: 选中 Key ...${key.takeLast(8)}")
                return key
            }
            if (bestOnCooldown == null || state.cooldownUntil < bestOnCooldown.second) {
                bestOnCooldown = key to state.cooldownUntil
            }
        }

        if (bestOnCooldown != null) {
            val remainMs = bestOnCooldown.second - now
            LingShuLog.w(moduleTag, "pickAvailableKey[${config.provider}]: 所有 ${allKeys.size} 个 Key 均在冷却，" +
                    "best-effort 选用 ...${bestOnCooldown.first.takeLast(8)}（剩余 ${remainMs}ms）")
            return bestOnCooldown.first
        }
        return allKeys.first()
    }

    /**
     * 标记某 Key 调用失败，进入冷却。
     * 连续失败会触发指数退避（60s -> 120s -> 240s ... 上限 30min）。
     */
    fun markFailed(provider: ModelProviderType, apiKey: String) {
        if (apiKey.isBlank()) return
        val sk = stateKey(provider, apiKey)
        val state = keyStates.computeIfAbsent(sk) { KeyState() }
        synchronized(state) {
            state.consecutiveFailures++
            val backoff = DEFAULT_COOLDOWN_MS * (1L shl (state.consecutiveFailures - 1).coerceAtMost(10))
            state.cooldownUntil = System.currentTimeMillis() + backoff.coerceAtMost(MAX_COOLDOWN_MS)
            LingShuLog.w(moduleTag, "markFailed[${provider}]: Key ...${apiKey.takeLast(8)} 标记冷却，" +
                    "连续失败=${state.consecutiveFailures}，冷却 ${backoff.coerceAtMost(MAX_COOLDOWN_MS) / 1000}s")
        }
    }

    /**
     * 标记某 Key 调用成功，重置其冷却状态。
     */
    fun markSuccess(provider: ModelProviderType, apiKey: String) {
        if (apiKey.isBlank()) return
        val sk = stateKey(provider, apiKey)
        val state = keyStates[sk]
        if (state != null) {
            synchronized(state) {
                if (state.consecutiveFailures > 0 || state.cooldownUntil > 0) {
                    LingShuLog.d(moduleTag, "markSuccess[${provider}]: Key ...${apiKey.takeLast(8)} 重置冷却状态")
                }
                state.consecutiveFailures = 0
                state.cooldownUntil = 0L
            }
        }
    }

    /**
     * 构造一个替换了 apiKey 的 config 副本。
     */
    fun withKey(config: LlmConfig, apiKey: String): LlmConfig {
        return config.copy(apiKey = apiKey)
    }

    /**
     * 清除指定 provider 的所有 Key 冷却状态。
     */
    fun resetProvider(provider: ModelProviderType) {
        val prefix = "${provider.name}::"
        val toRemove = keyStates.keys.filter { it.startsWith(prefix) }
        toRemove.forEach { keyStates.remove(it) }
        LingShuLog.i(moduleTag, "resetProvider[${provider}]: 清除 ${toRemove.size} 个 Key 状态")
    }
}
