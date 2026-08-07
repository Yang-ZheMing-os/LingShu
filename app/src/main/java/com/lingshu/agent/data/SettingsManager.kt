package com.lingshu.agent.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用设置管理器（模块1：DeepSeek API Key 管理）
 *
 * 使用 DataStore 读写 API Key，提供 Flow<String?> 供 UI 观察。
 * 后续模块可扩展更多设置项。
 */
@Singleton
class SettingsManager @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        /** DeepSeek API Key 的 DataStore Key */
        private val KEY_DEEPSEEK_API_KEY = stringPreferencesKey("deepseek_api_key")
    }

    /**
     * API Key 的响应式流，UI 可通过 collect 观察变化
     */
    val apiKeyFlow: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_DEEPSEEK_API_KEY]
    }

    /**
     * 挂起函数：读取当前 API Key（一次性）
     * @return API Key 字符串，未设置时返回 null
     */
    suspend fun getApiKey(): String? {
        return dataStore.data.map { it[KEY_DEEPSEEK_API_KEY] }.first()
    }

    /**
     * 挂起函数：写入 API Key
     * @param apiKey DeepSeek API Key
     */
    suspend fun setApiKey(apiKey: String) {
        dataStore.edit { it[KEY_DEEPSEEK_API_KEY] = apiKey }
    }

    /**
     * 挂起函数：清除 API Key
     */
    suspend fun clearApiKey() {
        dataStore.edit { it.remove(KEY_DEEPSEEK_API_KEY) }
    }

    /**
     * 挂起函数：判断 API Key 是否已配置
     */
    suspend fun hasApiKey(): Boolean {
        return !getApiKey().isNullOrBlank()
    }
}
