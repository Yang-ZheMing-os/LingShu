package com.lingshu.agent.core.model.routing

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * 模型配置仓库
 *
 * 使用 Jetpack DataStore Preferences 实现配置持久化
 * 特点：
 * - 异步读写，不阻塞主线程
 * - 类型安全
 * - 支持配置即时生效（通过Flow订阅）
 * - 无需重启应用
 *
 * 配置存储结构：
 * 全局设置 + 各模型独立配置
 */
class ModelConfigRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        /** DataStore 名称 */
        private const val DATASTORE_NAME = "model_config"

        /** Context 扩展属性创建 DataStore 实例 */
        private val Context.modelConfigDataStore: DataStore<Preferences> by preferencesDataStore(
            name = DATASTORE_NAME
        )

        // ============ 全局配置 Key ============
        private val KEY_DEFAULT_CHAT_MODEL = stringPreferencesKey("default_chat_model")
        private val KEY_DEFAULT_VISION_MODEL = stringPreferencesKey("default_vision_model")
        private val KEY_DEFAULT_TRANSCRIBE_MODEL = stringPreferencesKey("default_transcribe_model")
        private val KEY_DEFAULT_SYNTHESIZE_MODEL = stringPreferencesKey("default_synthesize_model")
        private val KEY_ENABLE_AUTO_FALLBACK = booleanPreferencesKey("enable_auto_fallback")
        private val KEY_ENABLE_API_KEY_ROTATION = booleanPreferencesKey("enable_api_key_rotation")

        // ============ 模型配置 Key 前缀 ============
        private const val PREFIX_ENABLED = "model_enabled_"
        private const val PREFIX_API_KEYS = "model_api_keys_"
        private const val PREFIX_BASE_URL = "model_base_url_"
        private const val PREFIX_MODEL_NAME = "model_model_name_"
        private const val PREFIX_PRIORITY = "model_priority_"
        private const val PREFIX_TEMPERATURE = "model_temperature_"
        private const val PREFIX_MAX_TOKENS = "model_max_tokens_"
        private const val PREFIX_TIMEOUT = "model_timeout_"
    }

    private val dataStore: DataStore<Preferences> = context.modelConfigDataStore

    /**
     * 配置 Flow - 订阅配置变更，实现即时生效
     */
    val configFlow: Flow<GlobalModelConfig> = dataStore.data.map { prefs ->
        loadConfigFromPreferences(prefs)
    }

    /**
     * 获取当前配置（一次性读取）
     */
    suspend fun getConfig(): GlobalModelConfig {
        return configFlow.first()
    }

    /**
     * 保存全局配置
     * @param config 要保存的全局配置
     */
    suspend fun saveConfig(config: GlobalModelConfig) {
        dataStore.edit { prefs ->
            // 保存全局设置
            prefs[KEY_DEFAULT_CHAT_MODEL] = config.defaultChatModel.name
            prefs[KEY_DEFAULT_VISION_MODEL] = config.defaultVisionModel.name
            prefs[KEY_DEFAULT_TRANSCRIBE_MODEL] = config.defaultTranscribeModel.name
            prefs[KEY_DEFAULT_SYNTHESIZE_MODEL] = config.defaultSynthesizeModel.name
            prefs[KEY_ENABLE_AUTO_FALLBACK] = config.enableAutoFallback
            prefs[KEY_ENABLE_API_KEY_ROTATION] = config.enableApiKeyRotation

            // 保存各模型配置
            config.modelConfigs.forEach { (modelType, modelConfig) ->
                saveModelConfigToPrefs(prefs, modelType, modelConfig)
            }
        }
    }

    /**
     * 更新默认对话模型
     */
    suspend fun setDefaultChatModel(modelType: ModelType) {
        updateSingleField(KEY_DEFAULT_CHAT_MODEL, modelType.name)
    }

    /**
     * 更新默认视觉模型
     */
    suspend fun setDefaultVisionModel(modelType: ModelType) {
        updateSingleField(KEY_DEFAULT_VISION_MODEL, modelType.name)
    }

    /**
     * 更新默认语音识别模型
     */
    suspend fun setDefaultTranscribeModel(modelType: ModelType) {
        updateSingleField(KEY_DEFAULT_TRANSCRIBE_MODEL, modelType.name)
    }

    /**
     * 更新默认语音合成模型
     */
    suspend fun setDefaultSynthesizeModel(modelType: ModelType) {
        updateSingleField(KEY_DEFAULT_SYNTHESIZE_MODEL, modelType.name)
    }

    /**
     * 设置自动降级开关
     */
    suspend fun setAutoFallbackEnabled(enabled: Boolean) {
        updateSingleField(KEY_ENABLE_AUTO_FALLBACK, enabled)
    }

    /**
     * 设置API Key轮询开关
     */
    suspend fun setApiKeyRotationEnabled(enabled: Boolean) {
        updateSingleField(KEY_ENABLE_API_KEY_ROTATION, enabled)
    }

    /**
     * 更新单个模型配置
     * 此方法用于设置页面修改单个模型配置（如API Key、启用状态等）
     * @param modelConfig 要保存的模型配置
     */
    suspend fun updateModelConfig(modelConfig: ModelConfig) {
        dataStore.edit { prefs ->
            saveModelConfigToPrefs(prefs, modelConfig.modelType, modelConfig)
        }
    }

    /**
     * 切换模型启用/禁用状态
     */
    suspend fun setModelEnabled(modelType: ModelType, enabled: Boolean) {
        val key = booleanPreferencesKey("$PREFIX_ENABLED${modelType.name}")
        updateSingleField(key, enabled)
    }

    /**
     * 更新模型的API Keys列表
     */
    suspend fun setModelApiKeys(modelType: ModelType, apiKeys: List<String>) {
        val key = stringSetPreferencesKey("$PREFIX_API_KEYS${modelType.name}")
        // 过滤空白Key并转为Set
        val validKeys = apiKeys.filter { it.isNotBlank() }.toSet()
        updateSingleField(key, validKeys)
    }

    /**
     * 重置为默认配置
     */
    suspend fun resetToDefault() {
        dataStore.edit { it.clear() }
    }

    // ==================== 内部方法 ====================

    /**
     * 从 Preferences 加载完整配置
     */
    private fun loadConfigFromPreferences(prefs: Preferences): GlobalModelConfig {
        val defaultConfigs = ModelType.values().associateWith { modelType ->
            loadModelConfigFromPrefs(prefs, modelType)
        }

        return GlobalModelConfig(
            defaultChatModel = ModelType.fromName(
                prefs[KEY_DEFAULT_CHAT_MODEL]
            ),
            defaultVisionModel = ModelType.fromName(
                prefs[KEY_DEFAULT_VISION_MODEL] ?: GlobalModelConfig.DEFAULT.defaultVisionModel.name
            ),
            defaultTranscribeModel = ModelType.fromName(
                prefs[KEY_DEFAULT_TRANSCRIBE_MODEL] ?: GlobalModelConfig.DEFAULT.defaultTranscribeModel.name
            ),
            defaultSynthesizeModel = ModelType.fromName(
                prefs[KEY_DEFAULT_SYNTHESIZE_MODEL] ?: GlobalModelConfig.DEFAULT.defaultSynthesizeModel.name
            ),
            enableAutoFallback = prefs[KEY_ENABLE_AUTO_FALLBACK] ?: GlobalModelConfig.DEFAULT.enableAutoFallback,
            enableApiKeyRotation = prefs[KEY_ENABLE_API_KEY_ROTATION]
                ?: GlobalModelConfig.DEFAULT.enableApiKeyRotation,
            modelConfigs = defaultConfigs
        )
    }

    /**
     * 从 Preferences 加载单个模型配置
     */
    private fun loadModelConfigFromPrefs(
        prefs: Preferences,
        modelType: ModelType
    ): ModelConfig {
        val defaultConfig = ModelConfig.default(modelType)

        val enabledKey = booleanPreferencesKey("$PREFIX_ENABLED${modelType.name}")
        val apiKeysKey = stringSetPreferencesKey("$PREFIX_API_KEYS${modelType.name}")
        val baseUrlKey = stringPreferencesKey("$PREFIX_BASE_URL${modelType.name}")
        val modelNameKey = stringPreferencesKey("$PREFIX_MODEL_NAME${modelType.name}")
        val priorityKey = intPreferencesKey("$PREFIX_PRIORITY${modelType.name}")
        val temperatureKey = floatPreferencesKey("$PREFIX_TEMPERATURE${modelType.name}")
        val maxTokensKey = intPreferencesKey("$PREFIX_MAX_TOKENS${modelType.name}")
        val timeoutKey = longPreferencesKey("$PREFIX_TIMEOUT${modelType.name}")

        return ModelConfig(
            modelType = modelType,
            apiKeys = prefs[apiKeysKey]?.toList() ?: defaultConfig.apiKeys,
            baseUrl = prefs[baseUrlKey] ?: defaultConfig.baseUrl,
            modelName = prefs[modelNameKey] ?: defaultConfig.modelName,
            isEnabled = prefs[enabledKey] ?: defaultConfig.isEnabled,
            priority = prefs[priorityKey] ?: defaultConfig.priority,
            temperature = prefs[temperatureKey] ?: defaultConfig.temperature,
            maxTokens = prefs[maxTokensKey] ?: defaultConfig.maxTokens,
            timeoutMs = prefs[timeoutKey] ?: defaultConfig.timeoutMs
        )
    }

    /**
     * 保存单个模型配置到 Preferences
     */
    private fun saveModelConfigToPrefs(
        prefs: MutablePreferences,
        modelType: ModelType,
        config: ModelConfig
    ) {
        prefs[booleanPreferencesKey("$PREFIX_ENABLED${modelType.name}")] = config.isEnabled
        prefs[stringSetPreferencesKey("$PREFIX_API_KEYS${modelType.name}")] =
            config.apiKeys.filter { it.isNotBlank() }.toSet()
        if (config.baseUrl != null) {
            prefs[stringPreferencesKey("$PREFIX_BASE_URL${modelType.name}")] = config.baseUrl!!
        }
        prefs[stringPreferencesKey("$PREFIX_MODEL_NAME${modelType.name}")] = config.modelName
        prefs[intPreferencesKey("$PREFIX_PRIORITY${modelType.name}")] = config.priority
        prefs[floatPreferencesKey("$PREFIX_TEMPERATURE${modelType.name}")] = config.temperature
        prefs[intPreferencesKey("$PREFIX_MAX_TOKENS${modelType.name}")] = config.maxTokens
        prefs[longPreferencesKey("$PREFIX_TIMEOUT${modelType.name}")] = config.timeoutMs
    }

    /**
     * 更新单个 String 字段的便捷方法
     */
    private suspend fun updateSingleField(key: Preferences.Key<String>, value: String) {
        dataStore.edit { prefs ->
            prefs[key] = value
        }
    }

    /**
     * 更新单个 Boolean 字段的便捷方法
     */
    private suspend fun updateSingleField(key: Preferences.Key<Boolean>, value: Boolean) {
        dataStore.edit { prefs ->
            prefs[key] = value
        }
    }

    /**
     * 更新单个 StringSet 字段的便捷方法
     */
    private suspend fun updateSingleField(key: Preferences.Key<Set<String>>, value: Set<String>) {
        dataStore.edit { prefs ->
            prefs[key] = value
        }
    }
}
