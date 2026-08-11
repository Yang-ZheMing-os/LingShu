package com.lingshu.core.data.llm

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lingshu.core.common.log.LingShuLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.llmDataStore by preferencesDataStore(name = "llm_config")

class LlmConfigStore(private val context: Context) {

    private val moduleTag = "LlmConfigStore"

    companion object {
        private const val DEFAULT_DEEPSEEK_URL = "https://api.deepseek.com/v1"
        private const val DEFAULT_DEEPSEEK_MODEL = "deepseek-chat"

        private const val DEFAULT_OLLAMA_URL = "http://10.0.2.2:11434"
        private const val DEFAULT_OLLAMA_MODEL = "qwen2.5:7b"

        private const val DEFAULT_GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta"
        private const val DEFAULT_GEMINI_MODEL = "gemini-1.5-flash"

        private const val DEFAULT_OPENAI_URL = "https://api.openai.com/v1"
        private const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini"

        private const val DEFAULT_QWEN_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
        private const val DEFAULT_QWEN_MODEL = "qwen-plus"
    }

    private object Keys {
        val SELECTED_PROVIDER = stringPreferencesKey("selected_provider")

        fun providerKey(provider: String, field: String) =
            stringPreferencesKey("llm_${provider}_${field}")

        fun providerIntKey(provider: String, field: String) =
            intPreferencesKey("llm_${provider}_${field}")

        fun providerFloatKey(provider: String, field: String) =
            floatPreferencesKey("llm_${provider}_${field}")
    }

    val selectedProvider: Flow<ModelProviderType> = context.llmDataStore.data
        .map { prefs ->
            val raw = prefs[Keys.SELECTED_PROVIDER] ?: ModelProviderType.DEEPSEEK.name
            try {
                ModelProviderType.valueOf(raw)
            } catch (_: Exception) {
                LingShuLog.w(moduleTag, "Invalid selectedProvider=$raw, fallback to DEEPSEEK")
                ModelProviderType.DEEPSEEK
            }
        }

    suspend fun setSelectedProvider(provider: ModelProviderType) {
        LingShuLog.i(moduleTag, "setSelectedProvider: ${provider.name}")
        context.llmDataStore.edit { prefs ->
            prefs[Keys.SELECTED_PROVIDER] = provider.name
        }
    }

    private fun defaultsFor(type: ModelProviderType): LlmConfig = when (type) {
        ModelProviderType.DEEPSEEK -> LlmConfig(
            provider = ModelProviderType.DEEPSEEK,
            baseUrl = DEFAULT_DEEPSEEK_URL,
            modelName = DEFAULT_DEEPSEEK_MODEL
        )
        ModelProviderType.OLLAMA -> LlmConfig(
            provider = ModelProviderType.OLLAMA,
            baseUrl = DEFAULT_OLLAMA_URL,
            modelName = DEFAULT_OLLAMA_MODEL
        )
        ModelProviderType.GEMINI -> LlmConfig(
            provider = ModelProviderType.GEMINI,
            baseUrl = DEFAULT_GEMINI_URL,
            modelName = DEFAULT_GEMINI_MODEL
        )
        ModelProviderType.OPENAI -> LlmConfig(
            provider = ModelProviderType.OPENAI,
            baseUrl = DEFAULT_OPENAI_URL,
            modelName = DEFAULT_OPENAI_MODEL
        )
        ModelProviderType.QWEN -> LlmConfig(
            provider = ModelProviderType.QWEN,
            baseUrl = DEFAULT_QWEN_URL,
            modelName = DEFAULT_QWEN_MODEL
        )
    }

    fun getConfigFlow(provider: ModelProviderType): Flow<LlmConfig> {
        val type = provider.name
        return context.llmDataStore.data.map { prefs ->
            val defaults = defaultsFor(provider)
            val config = LlmConfig(
                provider = provider,
                baseUrl = prefs[Keys.providerKey(type, "baseUrl")] ?: defaults.baseUrl,
                apiKey = prefs[Keys.providerKey(type, "apiKey")] ?: defaults.apiKey,
                modelName = prefs[Keys.providerKey(type, "modelName")] ?: defaults.modelName,
                temperature = prefs[Keys.providerFloatKey(type, "temperature")] ?: defaults.temperature,
                topP = prefs[Keys.providerFloatKey(type, "topP")] ?: defaults.topP,
                maxTokens = prefs[Keys.providerIntKey(type, "maxTokens")] ?: defaults.maxTokens,
                timeoutSeconds = prefs[Keys.providerIntKey(type, "timeoutSeconds")] ?: defaults.timeoutSeconds,
                systemPromptOverride = prefs[Keys.providerKey(type, "systemPromptOverride")]
            )
            LingShuLog.d(moduleTag, "getConfigFlow[$provider]: model=${config.modelName} | " +
                    "url=${config.baseUrl} | hasApiKey=${config.apiKey.isNotBlank()}")
            config
        }
    }

    suspend fun getConfig(provider: ModelProviderType): LlmConfig {
        return getConfigFlow(provider).first()
    }

    suspend fun saveConfig(config: LlmConfig) {
        val type = config.provider.name
        LingShuLog.i(moduleTag, "saveConfig[$type]: model=${config.modelName} | " +
                "baseUrl=${config.baseUrl} | apiKeyLen=${config.apiKey.length} | " +
                "temp=${config.temperature} | topP=${config.topP} | maxTokens=${config.maxTokens} | " +
                "timeout=${config.timeoutSeconds}s | sysOverride=${config.systemPromptOverride != null}")

        context.llmDataStore.edit { prefs ->
            prefs[Keys.providerKey(type, "baseUrl")] = config.baseUrl
            prefs[Keys.providerKey(type, "apiKey")] = config.apiKey
            prefs[Keys.providerKey(type, "modelName")] = config.modelName
            prefs[Keys.providerFloatKey(type, "temperature")] = config.temperature
            prefs[Keys.providerFloatKey(type, "topP")] = config.topP
            prefs[Keys.providerIntKey(type, "maxTokens")] = config.maxTokens
            prefs[Keys.providerIntKey(type, "timeoutSeconds")] = config.timeoutSeconds
            val sp = config.systemPromptOverride
            if (sp != null) {
                prefs[Keys.providerKey(type, "systemPromptOverride")] = sp
            } else {
                prefs.remove(Keys.providerKey(type, "systemPromptOverride"))
            }
        }
    }

    suspend fun getSelectedConfig(): LlmConfig {
        val provider = selectedProvider.first()
        LingShuLog.i(moduleTag, "getSelectedConfig: provider=$provider")
        return getConfig(provider)
    }

    suspend fun getAllConfigs(): Map<ModelProviderType, LlmConfig> {
        val all = ModelProviderType.values().associateWith { provider ->
            getConfig(provider)
        }
        LingShuLog.d(moduleTag, "getAllConfigs: total=${all.size}")
        return all
    }

    suspend fun resetProviderConfig(provider: ModelProviderType) {
        val type = provider.name
        LingShuLog.i(moduleTag, "resetProviderConfig: $provider")
        context.llmDataStore.edit { prefs ->
            prefs.remove(Keys.providerKey(type, "baseUrl"))
            prefs.remove(Keys.providerKey(type, "apiKey"))
            prefs.remove(Keys.providerKey(type, "modelName"))
            prefs.remove(Keys.providerFloatKey(type, "temperature"))
            prefs.remove(Keys.providerFloatKey(type, "topP"))
            prefs.remove(Keys.providerIntKey(type, "maxTokens"))
            prefs.remove(Keys.providerIntKey(type, "timeoutSeconds"))
            prefs.remove(Keys.providerKey(type, "systemPromptOverride"))
        }
    }

    suspend fun clearAll() {
        LingShuLog.w(moduleTag, "clearAll: removing ALL LLM configs")
        context.llmDataStore.edit { it.clear() }
    }
}
