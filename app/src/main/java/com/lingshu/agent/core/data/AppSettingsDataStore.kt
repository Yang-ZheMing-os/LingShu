package com.lingshu.agent.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lingshu.agent.feature.floating.FloatingBubbleManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用全局设置 DataStore 封装
 *
 * 存储范围：跨模块共享的用户偏好设置（悬浮窗、UI主题、语音、启动模式等）
 *
 * 模块专用的复杂配置（如ProactiveConfig、ModelConfigRepository）各自独立管理，
 * 这里只放通用设置，避免单文件膨胀。
 */
@Singleton
class AppSettingsDataStore @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {

    // ==================== Key 定义 ====================

    private object Keys {
        val THEME_MODE = stringPreferencesKey("app_theme_mode")

        val FLOATING_ENABLED = booleanPreferencesKey("floating_enabled")
        val FLOATING_BUBBLE_SIZE_DP = intPreferencesKey("floating_bubble_size_dp")
        val FLOATING_BUBBLE_ALPHA = floatPreferencesKey("floating_bubble_alpha")
        val FLOATING_AUTO_START = booleanPreferencesKey("floating_auto_start_on_boot")
        val FLOATING_BUBBLE_POS_X = floatPreferencesKey("floating_bubble_pos_x")
        val FLOATING_BUBBLE_POS_Y = floatPreferencesKey("floating_bubble_pos_y")

        val VOICE_TTS_ENABLED = booleanPreferencesKey("voice_tts_enabled")
        val VOICE_TTS_VOICE_ID = stringPreferencesKey("voice_tts_voice_id")
        val VOICE_TTS_SPEED = floatPreferencesKey("voice_tts_speed")
        val VOICE_ASR_HOTWORD = stringPreferencesKey("voice_asr_hotword")

        // P1 语音模块：VAD 可调参数
        val VOICE_VAD_SILENCE_THRESHOLD_DB = floatPreferencesKey("voice_vad_silence_threshold_db")
        val VOICE_VAD_TIMEOUT_MS = intPreferencesKey("voice_vad_timeout_ms")
        val VOICE_VAD_MIN_SPEECH_MS = intPreferencesKey("voice_vad_min_speech_ms")

        // P1 语音模块：Porcupine 唤醒词引擎 AccessKey
        val VOICE_PORCUPINE_ACCESS_KEY = stringPreferencesKey("voice_porcupine_access_key")

        val HEALTH_MONITOR_ENABLED = booleanPreferencesKey("health_monitor_enabled")
        val HEALTH_HR_MIN = intPreferencesKey("health_hr_min_threshold")
        val HEALTH_HR_MAX = intPreferencesKey("health_hr_max_threshold")
        val HEALTH_SEDENTARY_MINUTES = intPreferencesKey("health_sedentary_minutes")

        val KNOWLEDGE_RAG_ENABLED = booleanPreferencesKey("knowledge_rag_enabled")
        val KNOWLEDGE_DEFAULT_TOP_K = intPreferencesKey("knowledge_default_top_k")

        // 权限申请状态追踪（P0权限流程）
        val PERMISSION_MICROPHONE_GRANTED = booleanPreferencesKey("permissions_microphone_granted")
        val PERMISSION_NOTIFICATION_GRANTED = booleanPreferencesKey("permissions_notification_granted")
        val PERMISSION_ACCESSIBILITY_GRANTED = booleanPreferencesKey("permissions_accessibility_granted")
        val PERMISSION_OVERLAY_GRANTED = booleanPreferencesKey("permissions_overlay_granted")
        val PERMISSION_BATTERY_OPT_GRANTED = booleanPreferencesKey("permissions_battery_opt_granted")
        val ALL_PERMISSIONS_DONE = booleanPreferencesKey("all_permissions_done")
    }

    // ==================== 主题 ====================

    enum class ThemeMode { LIGHT, DARK, FOLLOW_SYSTEM }

    val themeModeFlow: Flow<ThemeMode> = dataStore.data.map { prefs ->
        val raw = prefs[Keys.THEME_MODE]
        if (raw != null) runCatching { ThemeMode.valueOf(raw) }.getOrDefault(ThemeMode.FOLLOW_SYSTEM)
        else ThemeMode.FOLLOW_SYSTEM
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    // ==================== 悬浮窗 ====================

    val isFloatingEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.FLOATING_ENABLED] ?: true
    }

    suspend fun setFloatingEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.FLOATING_ENABLED] = enabled }
    }

    val floatingBubbleSizeFlow: Flow<Int> = dataStore.data.map { prefs ->
        prefs[Keys.FLOATING_BUBBLE_SIZE_DP] ?: FloatingBubbleManager.DEFAULT_BUBBLE_SIZE_DP
    }

    suspend fun setFloatingBubbleSize(sizeDp: Int) {
        dataStore.edit { it[Keys.FLOATING_BUBBLE_SIZE_DP] = sizeDp.coerceIn(40, 120) }
    }

    val floatingBubbleAlphaFlow: Flow<Float> = dataStore.data.map { prefs ->
        prefs[Keys.FLOATING_BUBBLE_ALPHA] ?: FloatingBubbleManager.DEFAULT_ALPHA
    }

    suspend fun setFloatingBubbleAlpha(alpha: Float) {
        dataStore.edit { it[Keys.FLOATING_BUBBLE_ALPHA] = alpha.coerceIn(0.3f, 1.0f) }
    }

    val isFloatingAutoStartFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.FLOATING_AUTO_START] ?: true
    }

    suspend fun setFloatingAutoStart(autoStart: Boolean) {
        dataStore.edit { it[Keys.FLOATING_AUTO_START] = autoStart }
    }

    val floatingBubblePosXFlow: Flow<Float> = dataStore.data.map { prefs ->
        prefs[Keys.FLOATING_BUBBLE_POS_X] ?: -1f
    }

    val floatingBubblePosYFlow: Flow<Float> = dataStore.data.map { prefs ->
        prefs[Keys.FLOATING_BUBBLE_POS_Y] ?: -1f
    }

    suspend fun setFloatingBubblePosition(x: Float, y: Float) {
        dataStore.edit {
            it[Keys.FLOATING_BUBBLE_POS_X] = x
            it[Keys.FLOATING_BUBBLE_POS_Y] = y
        }
    }

    // ==================== 语音 ====================

    val isTtsEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.VOICE_TTS_ENABLED] ?: true
    }

    suspend fun setTtsEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.VOICE_TTS_ENABLED] = enabled }
    }

    val ttsVoiceIdFlow: Flow<String?> = dataStore.data.map { prefs ->
        prefs[Keys.VOICE_TTS_VOICE_ID]
    }

    suspend fun setTtsVoiceId(id: String?) {
        if (id == null) {
            dataStore.edit { it.remove(Keys.VOICE_TTS_VOICE_ID) }
        } else {
            dataStore.edit { it[Keys.VOICE_TTS_VOICE_ID] = id }
        }
    }

    val ttsSpeedFlow: Flow<Float> = dataStore.data.map { prefs ->
        prefs[Keys.VOICE_TTS_SPEED] ?: 1.0f
    }

    suspend fun setTtsSpeed(speed: Float) {
        dataStore.edit { it[Keys.VOICE_TTS_SPEED] = speed.coerceIn(0.5f, 2.0f) }
    }

    val asrHotwordFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.VOICE_ASR_HOTWORD] ?: "灵枢灵枢"
    }

    suspend fun setAsrHotword(word: String) {
        dataStore.edit { it[Keys.VOICE_ASR_HOTWORD] = word }
    }

    // ==================== P1 语音模块：VAD 参数 ====================

    /** 静音阈值 (dB)，默认 -40dB */
    val vadSilenceThresholdDbFlow: Flow<Float> = dataStore.data.map { prefs ->
        prefs[Keys.VOICE_VAD_SILENCE_THRESHOLD_DB] ?: -40f
    }

    suspend fun setVadSilenceThresholdDb(db: Float) {
        dataStore.edit { it[Keys.VOICE_VAD_SILENCE_THRESHOLD_DB] = db.coerceIn(-60f, -10f) }
    }

    /** VAD 静音超时时长 (ms)，默认 3000ms */
    val vadTimeoutMsFlow: Flow<Int> = dataStore.data.map { prefs ->
        prefs[Keys.VOICE_VAD_TIMEOUT_MS] ?: 3000
    }

    suspend fun setVadTimeoutMs(ms: Int) {
        dataStore.edit { it[Keys.VOICE_VAD_TIMEOUT_MS] = ms.coerceIn(1000, 10000) }
    }

    /** VAD 最小语音时长 (ms)，默认 500ms */
    val vadMinSpeechMsFlow: Flow<Int> = dataStore.data.map { prefs ->
        prefs[Keys.VOICE_VAD_MIN_SPEECH_MS] ?: 500
    }

    suspend fun setVadMinSpeechMs(ms: Int) {
        dataStore.edit { it[Keys.VOICE_VAD_MIN_SPEECH_MS] = ms.coerceIn(200, 2000) }
    }

    // ==================== P1 语音模块：Porcupine AccessKey ====================

    val porcupineAccessKeyFlow: Flow<String?> = dataStore.data.map { prefs ->
        prefs[Keys.VOICE_PORCUPINE_ACCESS_KEY]
    }

    suspend fun setPorcupineAccessKey(key: String?) {
        if (key == null || key.isBlank()) {
            dataStore.edit { it.remove(Keys.VOICE_PORCUPINE_ACCESS_KEY) }
        } else {
            dataStore.edit { it[Keys.VOICE_PORCUPINE_ACCESS_KEY] = key.trim() }
        }
    }

    // ==================== 健康监测默认阈值 ====================

    val isHealthMonitorEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.HEALTH_MONITOR_ENABLED] ?: true
    }

    suspend fun setHealthMonitorEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.HEALTH_MONITOR_ENABLED] = enabled }
    }

    val healthHrMinThresholdFlow: Flow<Int> = dataStore.data.map { prefs ->
        prefs[Keys.HEALTH_HR_MIN] ?: 50
    }

    suspend fun setHealthHrMinThreshold(hr: Int) {
        dataStore.edit { it[Keys.HEALTH_HR_MIN] = hr.coerceIn(30, 70) }
    }

    val healthHrMaxThresholdFlow: Flow<Int> = dataStore.data.map { prefs ->
        prefs[Keys.HEALTH_HR_MAX] ?: 120
    }

    suspend fun setHealthHrMaxThreshold(hr: Int) {
        dataStore.edit { it[Keys.HEALTH_HR_MAX] = hr.coerceIn(90, 200) }
    }

    val healthSedentaryMinutesFlow: Flow<Int> = dataStore.data.map { prefs ->
        prefs[Keys.HEALTH_SEDENTARY_MINUTES] ?: 90
    }

    suspend fun setHealthSedentaryMinutes(minutes: Int) {
        dataStore.edit { it[Keys.HEALTH_SEDENTARY_MINUTES] = minutes.coerceIn(15, 240) }
    }

    // ==================== RAG 知识库 ====================

    val isRagEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.KNOWLEDGE_RAG_ENABLED] ?: true
    }

    suspend fun setRagEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.KNOWLEDGE_RAG_ENABLED] = enabled }
    }

    val knowledgeDefaultTopKFlow: Flow<Int> = dataStore.data.map { prefs ->
        prefs[Keys.KNOWLEDGE_DEFAULT_TOP_K] ?: 5
    }

    suspend fun setKnowledgeDefaultTopK(k: Int) {
        dataStore.edit { it[Keys.KNOWLEDGE_DEFAULT_TOP_K] = k.coerceIn(1, 20) }
    }

    // ==================== 权限申请流程 ====================

    val allPermissionsDoneFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.ALL_PERMISSIONS_DONE] ?: false
    }

    suspend fun setAllPermissionsDone(done: Boolean) {
        dataStore.edit { it[Keys.ALL_PERMISSIONS_DONE] = done }
    }

    suspend fun setPermissionGranted(key: String) {
        dataStore.edit { prefs ->
            prefs[booleanPreferencesKey(key)] = true
        }
    }
}
