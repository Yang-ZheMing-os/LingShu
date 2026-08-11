package com.lingshu.agent.feature.model

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 模型设置仓库
 *
 * 使用 DataStore Preferences 持久化存储用户的模型配置：
 * 1. 用户选择的默认模型（各场景独立配置）
 * 2. 各模型提供者的 API Keys（支持多Key轮询）
 * 3. 各模型提供者的自定义 Base URL
 * 4. 全局开关（自动降级、API Key轮询等）
 *
 * 配置变更通过 Flow 实时通知，无需重启应用即可生效。
 */
@Singleton
class ModelSettings @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {

    // ==================== Preferences Key 定义 ====================

    companion object {
        // ---------- 默认模型配置（按场景） ----------
        /** 默认对话模型 Provider ID */
        private val KEY_DEFAULT_CHAT_PROVIDER = stringPreferencesKey("model_default_chat_provider")

        /** 默认视觉模型 Provider ID */
        private val KEY_DEFAULT_VISION_PROVIDER = stringPreferencesKey("model_default_vision_provider")

        /** 默认语音识别模型 Provider ID */
        private val KEY_DEFAULT_TRANSCRIBE_PROVIDER = stringPreferencesKey("model_default_transcribe_provider")

        /** 默认语音合成模型 Provider ID */
        private val KEY_DEFAULT_SYNTHESIZE_PROVIDER = stringPreferencesKey("model_default_synthesize_provider")

        // ---------- 全局开关 ----------
        /** 是否启用自动降级（云端不可用时降级到本地模型） */
        private val KEY_AUTO_FALLBACK_ENABLED = booleanPreferencesKey("model_auto_fallback_enabled")

        /** 是否启用 API Key 轮询（多Key时轮询使用） */
        private val KEY_API_KEY_ROTATION_ENABLED = booleanPreferencesKey("model_api_key_rotation_enabled")

        // ---------- 各 Provider 的 API Keys ----------
        // 使用前缀 + providerId 作为 key，值为 Set<String> 存储多个 API Key
        private const val PREFIX_API_KEYS = "model_api_keys_"

        // ---------- 各 Provider 的自定义 Base URL ----------
        private const val PREFIX_BASE_URL = "model_base_url_"

        // ---------- 各 Provider 的启用状态 ----------
        private const val PREFIX_ENABLED = "model_enabled_"

        // ---------- 各 Provider 的具体模型名称 ----------
        private const val PREFIX_MODEL_NAME = "model_name_"

        // ---------- 默认值常量 ----------
        /** 默认对话模型：DeepSeek */
        const val DEFAULT_CHAT_PROVIDER = "deepseek"

        /** 默认视觉模型：GPT-4V */
        const val DEFAULT_VISION_PROVIDER = "gpt4-vision"

        /** 默认语音识别模型：Vosk（离线） */
        const val DEFAULT_TRANSCRIBE_PROVIDER = "vosk"

        /** 默认语音合成模型：系统TTS */
        const val DEFAULT_SYNTHESIZE_PROVIDER = "system-tts"
    }

    // ==================== 内部辅助方法 ====================

    /** 构建 Provider API Keys 的 Preferences Key */
    private fun apiKeysKey(providerId: String) =
        stringSetPreferencesKey("$PREFIX_API_KEYS$providerId")

    /** 构建 Provider Base URL 的 Preferences Key */
    private fun baseUrlKey(providerId: String) =
        stringPreferencesKey("$PREFIX_BASE_URL$providerId")

    /** 构建 Provider 启用状态的 Preferences Key */
    private fun enabledKey(providerId: String) =
        booleanPreferencesKey("$PREFIX_ENABLED$providerId")

    /** 构建 Provider 模型名称的 Preferences Key */
    private fun modelNameKey(providerId: String) =
        stringPreferencesKey("$PREFIX_MODEL_NAME$providerId")

    // ==================== 配置 Flow（响应式读取） ====================

    /** 默认对话模型 Provider ID Flow */
    val defaultChatProviderFlow: Flow<String> = dataStore.data
        .map { it[KEY_DEFAULT_CHAT_PROVIDER] ?: DEFAULT_CHAT_PROVIDER }

    /** 默认视觉模型 Provider ID Flow */
    val defaultVisionProviderFlow: Flow<String> = dataStore.data
        .map { it[KEY_DEFAULT_VISION_PROVIDER] ?: DEFAULT_VISION_PROVIDER }

    /** 默认语音识别模型 Provider ID Flow */
    val defaultTranscribeProviderFlow: Flow<String> = dataStore.data
        .map { it[KEY_DEFAULT_TRANSCRIBE_PROVIDER] ?: DEFAULT_TRANSCRIBE_PROVIDER }

    /** 默认语音合成模型 Provider ID Flow */
    val defaultSynthesizeProviderFlow: Flow<String> = dataStore.data
        .map { it[KEY_DEFAULT_SYNTHESIZE_PROVIDER] ?: DEFAULT_SYNTHESIZE_PROVIDER }

    /** 自动降级开关 Flow */
    val autoFallbackEnabledFlow: Flow<Boolean> = dataStore.data
        .map { it[KEY_AUTO_FALLBACK_ENABLED] ?: true }

    /** API Key 轮询开关 Flow */
    val apiKeyRotationEnabledFlow: Flow<Boolean> = dataStore.data
        .map { it[KEY_API_KEY_ROTATION_ENABLED] ?: true }

    /**
     * 获取指定 Provider 的 API Keys Flow
     *
     * @param providerId 模型提供者ID
     * @return API Key 列表 Flow
     */
    fun getProviderApiKeysFlow(providerId: String): Flow<List<String>> = dataStore.data
        .map { prefs ->
            prefs[apiKeysKey(providerId)]?.toList() ?: emptyList()
        }

    /**
     * 获取指定 Provider 的自定义 Base URL Flow
     *
     * @param providerId 模型提供者ID
     * @return Base URL Flow（null表示使用默认URL）
     */
    fun getProviderBaseUrlFlow(providerId: String): Flow<String?> = dataStore.data
        .map { prefs -> prefs[baseUrlKey(providerId)] }

    /**
     * 获取指定 Provider 的启用状态 Flow
     *
     * @param providerId 模型提供者ID
     * @return 是否启用 Flow（默认启用）
     */
    fun getProviderEnabledFlow(providerId: String): Flow<Boolean> = dataStore.data
        .map { prefs -> prefs[enabledKey(providerId)] ?: true }

    /**
     * 获取指定 Provider 的具体模型名称 Flow
     *
     * @param providerId 模型提供者ID
     * @param defaultName 默认模型名称
     * @return 模型名称 Flow
     */
    fun getProviderModelNameFlow(providerId: String, defaultName: String): Flow<String> =
        dataStore.data.map { prefs ->
            prefs[modelNameKey(providerId)] ?: defaultName
        }

    // ==================== 单次读取（挂起函数） ====================

    /**
     * 获取指定能力对应的默认模型 Provider ID
     *
     * @param capability 模型能力
     * @return 默认 Provider ID
     */
    suspend fun getDefaultProviderForCapability(capability: ModelCapability): String {
        return when (capability) {
            ModelCapability.CHAT -> defaultChatProviderFlow.first()
            ModelCapability.VISION -> defaultVisionProviderFlow.first()
            ModelCapability.TRANSCRIBE -> defaultTranscribeProviderFlow.first()
            ModelCapability.SYNTHESIZE -> defaultSynthesizeProviderFlow.first()
        }
    }

    /**
     * 获取指定 Provider 的 API Keys 列表
     */
    suspend fun getProviderApiKeys(providerId: String): List<String> {
        return getProviderApiKeysFlow(providerId).first()
    }

    /**
     * 获取指定 Provider 的自定义 Base URL
     */
    suspend fun getProviderBaseUrl(providerId: String): String? {
        return getProviderBaseUrlFlow(providerId).first()
    }

    /**
     * 获取指定 Provider 是否启用
     */
    suspend fun isProviderEnabled(providerId: String): Boolean {
        return getProviderEnabledFlow(providerId).first()
    }

    /**
     * 是否启用自动降级
     */
    suspend fun isAutoFallbackEnabled(): Boolean {
        return autoFallbackEnabledFlow.first()
    }

    /**
     * 是否启用 API Key 轮询
     */
    suspend fun isApiKeyRotationEnabled(): Boolean {
        return apiKeyRotationEnabledFlow.first()
    }

    // ==================== 写入操作 ====================

    /**
     * 设置指定能力场景的默认模型 Provider
     *
     * @param capability 能力场景
     * @param providerId 要设为默认的 Provider ID
     */
    suspend fun setDefaultProvider(capability: ModelCapability, providerId: String) {
        val key = when (capability) {
            ModelCapability.CHAT -> KEY_DEFAULT_CHAT_PROVIDER
            ModelCapability.VISION -> KEY_DEFAULT_VISION_PROVIDER
            ModelCapability.TRANSCRIBE -> KEY_DEFAULT_TRANSCRIBE_PROVIDER
            ModelCapability.SYNTHESIZE -> KEY_DEFAULT_SYNTHESIZE_PROVIDER
        }
        dataStore.edit { it[key] = providerId }
    }

    /**
     * 便捷方法：设置默认对话模型
     */
    suspend fun setDefaultChatProvider(providerId: String) {
        setDefaultProvider(ModelCapability.CHAT, providerId)
    }

    /**
     * 便捷方法：设置默认视觉模型
     */
    suspend fun setDefaultVisionProvider(providerId: String) {
        setDefaultProvider(ModelCapability.VISION, providerId)
    }

    /**
     * 便捷方法：设置默认语音识别模型
     */
    suspend fun setDefaultTranscribeProvider(providerId: String) {
        setDefaultProvider(ModelCapability.TRANSCRIBE, providerId)
    }

    /**
     * 便捷方法：设置默认语音合成模型
     */
    suspend fun setDefaultSynthesizeProvider(providerId: String) {
        setDefaultProvider(ModelCapability.SYNTHESIZE, providerId)
    }

    /**
     * 设置自动降级开关
     */
    suspend fun setAutoFallbackEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_AUTO_FALLBACK_ENABLED] = enabled }
    }

    /**
     * 设置 API Key 轮询开关
     */
    suspend fun setApiKeyRotationEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_API_KEY_ROTATION_ENABLED] = enabled }
    }

    /**
     * 设置指定 Provider 的 API Keys 列表
     * 支持多个 API Key 以实现轮询和故障转移
     *
     * @param providerId 模型提供者ID
     * @param apiKeys API Key 列表（传空列表表示清除）
     */
    suspend fun setProviderApiKeys(providerId: String, apiKeys: List<String>) {
        dataStore.edit { prefs ->
            if (apiKeys.isEmpty()) {
                prefs.remove(apiKeysKey(providerId))
            } else {
                prefs[apiKeysKey(providerId)] = apiKeys.toSet()
            }
        }
    }

    /**
     * 添加单个 API Key 到指定 Provider
     *
     * @param providerId 模型提供者ID
     * @param apiKey 要添加的 API Key
     */
    suspend fun addApiKey(providerId: String, apiKey: String) {
        if (apiKey.isBlank()) return
        val current = getProviderApiKeys(providerId).toMutableList()
        if (!current.contains(apiKey)) {
            current.add(apiKey)
            setProviderApiKeys(providerId, current)
        }
    }

    /**
     * 移除指定 Provider 的某个 API Key
     *
     * @param providerId 模型提供者ID
     * @param index 要移除的 Key 索引
     */
    suspend fun removeApiKey(providerId: String, index: Int) {
        val current = getProviderApiKeys(providerId).toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            setProviderApiKeys(providerId, current)
        }
    }

    /**
     * 设置指定 Provider 的自定义 Base URL
     *
     * @param providerId 模型提供者ID
     * @param baseUrl 自定义 Base URL，传 null 表示使用默认
     */
    suspend fun setProviderBaseUrl(providerId: String, baseUrl: String?) {
        dataStore.edit { prefs ->
            if (baseUrl.isNullOrBlank()) {
                prefs.remove(baseUrlKey(providerId))
            } else {
                prefs[baseUrlKey(providerId)] = baseUrl
            }
        }
    }

    /**
     * 设置指定 Provider 的启用/禁用状态
     */
    suspend fun setProviderEnabled(providerId: String, enabled: Boolean) {
        dataStore.edit { it[enabledKey(providerId)] = enabled }
    }

    /**
     * 设置指定 Provider 的具体模型名称
     * 例如：deepseek-chat, gpt-4o, llama3.1 等
     */
    suspend fun setProviderModelName(providerId: String, modelName: String) {
        dataStore.edit { it[modelNameKey(providerId)] = modelName }
    }

    /**
     * 重置所有模型设置为默认值
     */
    suspend fun resetToDefaults() {
        dataStore.edit { prefs ->
            // 清除所有以 model_ 开头的 key
            val keysToRemove = prefs.asMap().keys.filter {
                it.name.startsWith("model_")
            }
            keysToRemove.forEach { prefs.remove(it) }
        }
    }
}
