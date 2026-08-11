package com.lingshu.agent.feature.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lingshu.agent.core.database.dao.MemoryDao
import com.lingshu.agent.core.database.entity.MemoryEntity
import com.lingshu.agent.feature.personality.PersonalityManager
import com.lingshu.agent.feature.personality.PersonalityState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 设置页 DataStore 持久化 ViewModel
 *
 * 管理主动关怀、数据加密等用户偏好的持久化读写。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val memoryDao: MemoryDao,
    private val personalityManager: PersonalityManager,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    companion object {
        val KEY_PROACTIVE_ENABLED = booleanPreferencesKey("proactive_enabled")
        val KEY_PROACTIVE_COOLDOWN = floatPreferencesKey("proactive_cooldown")
        val KEY_PROACTIVE_DAILY_LIMIT = stringPreferencesKey("proactive_daily_limit")
        val KEY_AES_ENABLED = booleanPreferencesKey("aes_enabled")
        val KEY_VOICE_VAD_SILENCE_THRESHOLD = floatPreferencesKey("voice_vad_silence_threshold_db")
        val KEY_VOICE_VAD_TIMEOUT = stringPreferencesKey("voice_vad_timeout_ms")
        val KEY_VOICE_VAD_MIN_SPEECH = stringPreferencesKey("voice_vad_min_speech_ms")
        val KEY_VOICE_PORCUPINE_ACCESS_KEY = stringPreferencesKey("voice_porcupine_access_key")
        val KEY_OTA_UPDATE_URL = stringPreferencesKey("ota_update_url")
    }

    // ==================== 主动关怀状态 ====================

    val proactiveEnabled: StateFlow<Boolean> = dataStore.data
        .map { it[KEY_PROACTIVE_ENABLED] ?: true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val proactiveCooldown: StateFlow<Float> = dataStore.data
        .map { it[KEY_PROACTIVE_COOLDOWN] ?: 60f }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 60f)

    val proactiveDailyLimit: StateFlow<String> = dataStore.data
        .map { it[KEY_PROACTIVE_DAILY_LIMIT] ?: "5" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "5")

    val aesEnabled: StateFlow<Boolean> = dataStore.data
        .map { it[KEY_AES_ENABLED] ?: true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    // ==================== 语音 VAD 参数状态 ====================

    val vadSilenceThresholdDb: StateFlow<Float> = dataStore.data
        .map { it[KEY_VOICE_VAD_SILENCE_THRESHOLD] ?: -40f }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), -40f)

    val vadTimeoutMs: StateFlow<String> = dataStore.data
        .map { it[KEY_VOICE_VAD_TIMEOUT] ?: "3000" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "3000")

    val vadMinSpeechMs: StateFlow<String> = dataStore.data
        .map { it[KEY_VOICE_VAD_MIN_SPEECH] ?: "500" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "500")

    val porcupineAccessKey: StateFlow<String> = dataStore.data
        .map { it[KEY_VOICE_PORCUPINE_ACCESS_KEY] ?: "" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    // ==================== OTA 更新状态 ====================

    val otaUpdateUrl: StateFlow<String> = dataStore.data
        .map { it[KEY_OTA_UPDATE_URL] ?: "" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    private val _otaChecking = kotlinx.coroutines.flow.MutableStateFlow(false)
    val otaChecking: StateFlow<Boolean> = _otaChecking

    private val _otaLatestVersion = kotlinx.coroutines.flow.MutableStateFlow("")
    val otaLatestVersion: StateFlow<String> = _otaLatestVersion

    // ==================== 长期记忆状态 ====================

    val allMemories: StateFlow<List<MemoryEntity>> = memoryDao.getAllMemories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ==================== 人格演化状态（模块6） ====================

    val personalityState: StateFlow<PersonalityState> = personalityManager.currentPersonality
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PersonalityState())

    fun updatePersonality(state: PersonalityState) {
        personalityManager.updatePersonality(state)
    }

    fun resetPersonality() {
        personalityManager.resetToDefault()
    }

    // ==================== 写入操作 ====================

    fun setProactiveEnabled(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.edit { it[KEY_PROACTIVE_ENABLED] = enabled }
        }
    }

    fun setProactiveCooldown(minutes: Float) {
        viewModelScope.launch {
            dataStore.edit { it[KEY_PROACTIVE_COOLDOWN] = minutes }
        }
    }

    fun setProactiveDailyLimit(limit: String) {
        viewModelScope.launch {
            dataStore.edit { it[KEY_PROACTIVE_DAILY_LIMIT] = limit }
        }
    }

    fun setAesEnabled(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.edit { it[KEY_AES_ENABLED] = enabled }
        }
    }

    fun setVadSilenceThresholdDb(db: Float) {
        viewModelScope.launch {
            dataStore.edit { it[KEY_VOICE_VAD_SILENCE_THRESHOLD] = db }
        }
    }

    fun setVadTimeoutMs(ms: String) {
        viewModelScope.launch {
            dataStore.edit { it[KEY_VOICE_VAD_TIMEOUT] = ms }
        }
    }

    fun setVadMinSpeechMs(ms: String) {
        viewModelScope.launch {
            dataStore.edit { it[KEY_VOICE_VAD_MIN_SPEECH] = ms }
        }
    }

    fun setPorcupineAccessKey(key: String) {
        viewModelScope.launch {
            dataStore.edit { it[KEY_VOICE_PORCUPINE_ACCESS_KEY] = key }
        }
    }

    fun setOtaUpdateUrl(url: String) {
        viewModelScope.launch {
            dataStore.edit { it[KEY_OTA_UPDATE_URL] = url }
        }
    }

    fun checkOtaUpdate() {
        viewModelScope.launch {
            _otaChecking.value = true
            try {
                val manager = com.lingshu.agent.feature.settings.OtaManager(appContext)
                when (val result = manager.checkForUpdate()) {
                    is com.lingshu.agent.feature.settings.CheckResult.UpdateAvailable -> {
                        _otaLatestVersion.value = result.info.tagName
                        android.widget.Toast.makeText(
                            appContext,
                            "发现新版本: ${result.info.tagName}",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                    is com.lingshu.agent.feature.settings.CheckResult.NoUpdate -> {
                        _otaLatestVersion.value = ""
                        android.widget.Toast.makeText(
                            appContext,
                            "当前已是最新版本",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    is com.lingshu.agent.feature.settings.CheckResult.Failed -> {
                        _otaLatestVersion.value = ""
                        android.widget.Toast.makeText(
                            appContext,
                            "检查失败: ${result.reason}",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                _otaLatestVersion.value = ""
                android.widget.Toast.makeText(
                    appContext,
                    "检查失败: ${e.message}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } finally {
                _otaChecking.value = false
            }
        }
    }

    /**
     * 清除所有设置页持久化数据
     */
    fun clearAllSettings() {
        viewModelScope.launch {
            dataStore.edit { it.clear() }
        }
    }

    /**
     * 删除单条记忆
     */
    fun deleteMemory(memory: MemoryEntity) {
        viewModelScope.launch {
            memoryDao.deleteMemory(memory)
        }
    }
}
