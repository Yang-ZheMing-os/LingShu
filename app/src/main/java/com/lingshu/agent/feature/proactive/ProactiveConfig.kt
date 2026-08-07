package com.lingshu.agent.feature.proactive

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.core.DataStore
import javax.inject.Inject
import javax.inject.Singleton

/** 规格书 P5-P6 定义的7种主动关怀触发类型 */
enum class SpecTrigger(val id: Int, val displayName: String, val description: String) {
    LATE_NIGHT(1, "深夜未睡", "23:30-05:00 + 屏幕亮屏，间隔30分钟"),
    MEAL_REMINDER(2, "饭点未进食", "07:00-09:00 / 11:30-13:30 / 17:30-19:30，间隔15分钟"),
    SEDENTARY(3, "久坐提醒", "连续静止>2小时（可调1-4小时），实时检测"),
    LOW_LIGHT(4, "暗光手电筒", "环境光<10 lux + 步态检测，实时"),
    HEART_RATE(5, "心率异常", "静息心率>100或<45 bpm，实时"),
    STRESS_INDEX(6, "压力指数", "HRV计算，指数>0.7，实时"),
    RAIN_UMBRELLA(7, "雨天带伞", "降水概率>60%，每小时")
}

enum class TriggerType(val displayName: String) {
    // ========== 规格书 P5-P6 7种核心触发类型 ==========
    TIME_LATE_NIGHT("深夜未睡"),
    MEAL_REMINDER("饭点未进食"),
    SENSOR_SEDENTARY("久坐提醒"),
    LOW_LIGHT_FLASHLIGHT("暗光手电筒"),
    SENSOR_HEART_RATE("心率异常"),
    STRESS_INDEX("压力指数"),
    RAIN_UMBRELLA("雨天带伞"),

    // ========== 扩展触发类型（非规格书核心，保留向后兼容） ==========
    TIME_FIXED("固定时间提醒"),
    TIME_USER_REMINDER("自定义提醒"),
    BEHAVIOR_UNLOCK("频繁解锁提醒"),
    BEHAVIOR_LATE_APP_USE("深夜刷手机提醒"),
    BEHAVIOR_LONG_APP_STAY("长时间使用同一应用提醒"),
    SENSOR_LONG_STILL("长时间静止提醒"),
    MEMORY_BIRTHDAY("生日关怀"),
    MEMORY_ANNIVERSARY("纪念日关怀"),
    MEMORY_NEGATIVE_MOOD("负面情绪跟进"),
    RANDOM("随机关怀"),

    // ========== 规格书触发大类别名 ==========
    // 向后兼容：specLateNight / specMeal / specSedentary / specLowLight / specHeartRate / specStress / specRain
}

enum class GenerationStrategy {
    RULE_BASED,
    MODEL_BASED,
    HYBRID
}

data class TriggerResult(
    val shouldTrigger: Boolean,
    val triggerType: TriggerType?,
    val reason: String,
    val confidence: Float
)

data class TriggerSnapshot(
    val currentHour: Int,
    val currentMinute: Int,
    val currentDayOfWeek: Int,
    val isScreenOn: Boolean,
    val unlockCountSinceMorning: Int,
    val lastUnlockTime: Long,
    val currentForegroundApp: String?,
    val foregroundAppStartTime: Long,
    val sedentaryMinutes: Int,
    val currentHeartRate: Int?,
    val heartRateThresholdMin: Int,
    val heartRateThresholdMax: Int,
    val stillMinutes: Int,
    val upcomingBirthdays: List<String>,
    val upcomingAnniversaries: List<String>,
    val lastNegativeMoodDays: Int,
    val lastCareTime: Long,
    val todayCareCount: Int,
    val totalDaysWithApp: Int,
    // ===== 规格书 P5-P6 新增传感器字段 =====
    val ambientLightLux: Int? = null,
    val stepCountLast5Min: Int = 0,
    val stressIndex: Float? = null,
    val rainProbability: Int? = null
)

data class CustomReminder(
    val id: String,
    val name: String,
    val hour: Int,
    val minute: Int,
    val daysOfWeek: List<Int>,
    val enabled: Boolean,
    val message: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

data class ProactiveConfigState(
    val enabled: Boolean = true,
    val cooldownMs: Long = 3_600_000L,
    val dailyLimit: Int = 5,
    val cooldownMinutes: Int = 60,
    val enabledTriggerTypes: Set<TriggerType> = TriggerType.values().toSet(),
    val generationStrategy: GenerationStrategy = GenerationStrategy.HYBRID,
    val generatorModelType: String = "GEMMA",
    // ========== 规格书 P5 阈值 ==========
    val sedentaryThresholdMinutes: Int = 120,
    val heartRateLower: Int = 45,
    val heartRateUpper: Int = 100,
    val stressIndexThreshold: Int = 70,
    val rainProbabilityThreshold: Int = 60,
    val lowLightLuxThreshold: Int = 10,
    // ========== 规格书 4.2 勿扰时段 ==========
    val quietHoursEnabled: Boolean = false,
    val quietHoursStart: Int = 2200,
    val quietHoursStartHour: Int = 22,
    val quietHoursStartMin: Int = 0,
    val quietHoursEnd: Int = 800,
    val quietHoursEndHour: Int = 8,
    val quietHoursEndMin: Int = 0
)

data class MemoryEvent(
    val id: String,
    val type: String,
    val title: String,
    val month: Int,
    val day: Int,
    val year: Int? = null,
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

object ProactiveKeys {
    val ENABLED = booleanPreferencesKey("proactive_enabled")
    val COOLDOWN_MINUTES = intPreferencesKey("proactive_cooldown_minutes")
    val DAILY_LIMIT = intPreferencesKey("proactive_daily_limit")
    val GENERATION_STRATEGY = stringPreferencesKey("proactive_generation_strategy")
    val GENERATOR_MODEL_TYPE = stringPreferencesKey("proactive_generator_model_type")
    val TRIGGER_TYPES = stringSetPreferencesKey("proactive_trigger_types")
    val RANDOM_PROBABILITY = intPreferencesKey("proactive_random_probability")
    val UNLOCK_THRESHOLD = intPreferencesKey("proactive_unlock_threshold")
    val SEDENTARY_THRESHOLD_MINUTES = intPreferencesKey("proactive_sedentary_threshold")
    val STILL_THRESHOLD_MINUTES = intPreferencesKey("proactive_still_threshold")
    val LONG_APP_THRESHOLD_MINUTES = intPreferencesKey("proactive_long_app_threshold")
    val LATE_NIGHT_START_HOUR = intPreferencesKey("proactive_late_night_start_hour")
    val LATE_NIGHT_END_HOUR = intPreferencesKey("proactive_late_night_end_hour")
    val HEART_RATE_MIN = intPreferencesKey("proactive_heart_rate_min")
    val HEART_RATE_MAX = intPreferencesKey("proactive_heart_rate_max")
    val CUSTOM_REMINDERS_JSON = stringPreferencesKey("proactive_custom_reminders_json")
    val MEMORY_EVENTS_JSON = stringPreferencesKey("proactive_memory_events_json")
    val AUTO_LAUNCH_CHAT = booleanPreferencesKey("proactive_auto_launch_chat")

    // ========== 规格书 P5-P6：quietHours / 各触发阈值 ==========
    val QUIET_HOURS_START = intPreferencesKey("proactive_quiet_hours_start")
    val QUIET_HOURS_END = intPreferencesKey("proactive_quiet_hours_end")
    val QUIET_HOURS_ENABLED = booleanPreferencesKey("proactive_quiet_hours_enabled")

    val SEDENTARY_THRESHOLD_HOURS = intPreferencesKey("proactive_sedentary_threshold_hours")
    val HEART_RATE_LOWER = intPreferencesKey("proactive_heart_rate_lower")
    val HEART_RATE_UPPER = intPreferencesKey("proactive_heart_rate_upper")
    val STRESS_INDEX_THRESHOLD = intPreferencesKey("proactive_stress_threshold")
    val RAIN_PROBABILITY_THRESHOLD = intPreferencesKey("proactive_rain_probability_threshold")
    val LOW_LIGHT_LUX_THRESHOLD = intPreferencesKey("proactive_low_light_lux_threshold")
}

@Singleton
class ProactiveConfig @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val gson: Gson
) {

    companion object {
        private const val DEFAULT_COOLDOWN_MINUTES = 60
        private const val DEFAULT_DAILY_LIMIT = 5
        private const val DEFAULT_RANDOM_PROBABILITY = 25
        private const val DEFAULT_UNLOCK_THRESHOLD = 5
        private const val DEFAULT_SEDENTARY_THRESHOLD = 120
        private const val DEFAULT_STILL_THRESHOLD = 60
        private const val DEFAULT_LONG_APP_THRESHOLD = 45
        private const val DEFAULT_LATE_NIGHT_START = 23
        private const val DEFAULT_LATE_NIGHT_END = 5
        private const val DEFAULT_HEART_RATE_MIN = 50
        private const val DEFAULT_HEART_RATE_MAX = 100
    }

    val enabled: Flow<Boolean> = dataStore.data
        .map { prefs -> prefs[ProactiveKeys.ENABLED] ?: true }

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[ProactiveKeys.ENABLED] = enabled }
    }

    val cooldownMinutes: Flow<Int> = dataStore.data
        .map { it[ProactiveKeys.COOLDOWN_MINUTES] ?: DEFAULT_COOLDOWN_MINUTES }

    suspend fun setCooldownMinutes(minutes: Int) {
        dataStore.edit { it[ProactiveKeys.COOLDOWN_MINUTES] = minutes.coerceIn(5, 720) }
    }

    val dailyLimit: Flow<Int> = dataStore.data
        .map { it[ProactiveKeys.DAILY_LIMIT] ?: DEFAULT_DAILY_LIMIT }

    suspend fun setDailyLimit(limit: Int) {
        dataStore.edit { it[ProactiveKeys.DAILY_LIMIT] = limit.coerceIn(0, 50) }
    }

    val generationStrategy: Flow<GenerationStrategy> = dataStore.data
        .map {
            val str = it[ProactiveKeys.GENERATION_STRATEGY]
            if (str != null) GenerationStrategy.valueOf(str) else GenerationStrategy.HYBRID
        }

    suspend fun setGenerationStrategy(strategy: GenerationStrategy) {
        dataStore.edit { it[ProactiveKeys.GENERATION_STRATEGY] = strategy.name }
    }

    val generatorModelType: Flow<String> = dataStore.data
        .map { it[ProactiveKeys.GENERATOR_MODEL_TYPE] ?: "GEMMA" }

    suspend fun setGeneratorModelType(type: String) {
        dataStore.edit { it[ProactiveKeys.GENERATOR_MODEL_TYPE] = type }
    }

    val enabledTriggerTypes: Flow<Set<TriggerType>> = dataStore.data
        .map { prefSet ->
            val strings = prefSet[ProactiveKeys.TRIGGER_TYPES]
            if (strings != null && strings.isNotEmpty()) {
                strings.mapNotNull { runCatching { TriggerType.valueOf(it) }.getOrNull() }.toSet()
            } else {
                TriggerType.values().toSet()
            }
        }

    suspend fun setEnabledTriggerTypes(triggers: Set<TriggerType>) {
        dataStore.edit {
            it[ProactiveKeys.TRIGGER_TYPES] = triggers.map { t -> t.name }.toSet()
        }
    }

    val randomProbability: Flow<Int> = dataStore.data
        .map { it[ProactiveKeys.RANDOM_PROBABILITY] ?: DEFAULT_RANDOM_PROBABILITY }

    suspend fun setRandomProbability(prob: Int) {
        dataStore.edit { it[ProactiveKeys.RANDOM_PROBABILITY] = prob.coerceIn(0, 100) }
    }

    val unlockThreshold: Flow<Int> = dataStore.data
        .map { it[ProactiveKeys.UNLOCK_THRESHOLD] ?: DEFAULT_UNLOCK_THRESHOLD }

    val sedentaryThresholdMinutes: Flow<Int> = dataStore.data
        .map { it[ProactiveKeys.SEDENTARY_THRESHOLD_MINUTES] ?: DEFAULT_SEDENTARY_THRESHOLD }

    val stillThresholdMinutes: Flow<Int> = dataStore.data
        .map { it[ProactiveKeys.STILL_THRESHOLD_MINUTES] ?: DEFAULT_STILL_THRESHOLD }

    val longAppThresholdMinutes: Flow<Int> = dataStore.data
        .map { it[ProactiveKeys.LONG_APP_THRESHOLD_MINUTES] ?: DEFAULT_LONG_APP_THRESHOLD }

    val lateNightHours: Flow<Pair<Int, Int>> = dataStore.data
        .map { prefs ->
            val start = prefs[ProactiveKeys.LATE_NIGHT_START_HOUR] ?: DEFAULT_LATE_NIGHT_START
            val end = prefs[ProactiveKeys.LATE_NIGHT_END_HOUR] ?: DEFAULT_LATE_NIGHT_END
            start to end
        }

    val heartRateRange: Flow<Pair<Int, Int>> = dataStore.data
        .map { prefs ->
            val min = prefs[ProactiveKeys.HEART_RATE_MIN] ?: DEFAULT_HEART_RATE_MIN
            val max = prefs[ProactiveKeys.HEART_RATE_MAX] ?: DEFAULT_HEART_RATE_MAX
            min to max
        }

    val customReminders: Flow<List<CustomReminder>> = dataStore.data
        .map { prefs ->
            val json = prefs[ProactiveKeys.CUSTOM_REMINDERS_JSON]
            if (json.isNullOrBlank()) {
                emptyList()
            } else {
                val type = object : TypeToken<List<CustomReminder>>() {}.type
                gson.fromJson(json, type)
            }
        }

    suspend fun addCustomReminder(reminder: CustomReminder) {
        val current = runCatching {
            val type = object : TypeToken<List<CustomReminder>>() {}.type
            gson.fromJson<List<CustomReminder>>(
                getCurrentJson(ProactiveKeys.CUSTOM_REMINDERS_JSON),
                type
            )
        }.getOrDefault(emptyList())
        val updated = (current + reminder).distinctBy { it.id }
        dataStore.edit {
            it[ProactiveKeys.CUSTOM_REMINDERS_JSON] = gson.toJson(updated)
        }
    }

    suspend fun removeCustomReminder(id: String) {
        val current = runCatching {
            val type = object : TypeToken<List<CustomReminder>>() {}.type
            gson.fromJson<List<CustomReminder>>(
                getCurrentJson(ProactiveKeys.CUSTOM_REMINDERS_JSON),
                type
            )
        }.getOrDefault(emptyList())
        val updated = current.filter { it.id != id }
        dataStore.edit {
            it[ProactiveKeys.CUSTOM_REMINDERS_JSON] = gson.toJson(updated)
        }
    }

    val memoryEvents: Flow<List<MemoryEvent>> = dataStore.data
        .map { prefs ->
            val json = prefs[ProactiveKeys.MEMORY_EVENTS_JSON]
            if (json.isNullOrBlank()) emptyList()
            else {
                val type = object : TypeToken<List<MemoryEvent>>() {}.type
                gson.fromJson(json, type)
            }
        }

    suspend fun addMemoryEvent(event: MemoryEvent) {
        val current = runCatching {
            val type = object : TypeToken<List<MemoryEvent>>() {}.type
            gson.fromJson<List<MemoryEvent>>(
                getCurrentJson(ProactiveKeys.MEMORY_EVENTS_JSON),
                type
            )
        }.getOrDefault(emptyList())
        val updated = (current + event).distinctBy { it.id }
        dataStore.edit {
            it[ProactiveKeys.MEMORY_EVENTS_JSON] = gson.toJson(updated)
        }
    }

    suspend fun removeMemoryEvent(id: String) {
        val current = runCatching {
            val type = object : TypeToken<List<MemoryEvent>>() {}.type
            gson.fromJson<List<MemoryEvent>>(
                getCurrentJson(ProactiveKeys.MEMORY_EVENTS_JSON),
                type
            )
        }.getOrDefault(emptyList())
        val updated = current.filter { it.id != id }
        dataStore.edit {
            it[ProactiveKeys.MEMORY_EVENTS_JSON] = gson.toJson(updated)
        }
    }

    // ==================== QuietHours（规格书 4.2） ====================

    val quietHoursEnabled: Flow<Boolean> = dataStore.data
        .map { prefs -> prefs[ProactiveKeys.QUIET_HOURS_ENABLED] ?: false }

    val quietHoursStart: Flow<Int> = dataStore.data
        .map { prefs -> prefs[ProactiveKeys.QUIET_HOURS_START] ?: 22 }

    val quietHoursEnd: Flow<Int> = dataStore.data
        .map { prefs -> prefs[ProactiveKeys.QUIET_HOURS_END] ?: 8 }

    suspend fun setQuietHours(enabled: Boolean, startHour: Int, endHour: Int) {
        dataStore.edit {
            it[ProactiveKeys.QUIET_HOURS_ENABLED] = enabled
            it[ProactiveKeys.QUIET_HOURS_START] = startHour.coerceIn(0, 23)
            it[ProactiveKeys.QUIET_HOURS_END] = endHour.coerceIn(0, 23)
        }
    }

    /** 单独设置勿扰开关 */
    suspend fun setQuietHoursEnabled(enabled: Boolean) {
        dataStore.edit { it[ProactiveKeys.QUIET_HOURS_ENABLED] = enabled }
    }

    /** 设置勿扰开始时间（hour 0-23） */
    suspend fun setQuietHoursStart(startHour: Int) {
        dataStore.edit { it[ProactiveKeys.QUIET_HOURS_START] = startHour.coerceIn(0, 23) }
    }

    /** 设置勿扰结束时间（hour 0-23） */
    suspend fun setQuietHoursEnd(endHour: Int) {
        dataStore.edit { it[ProactiveKeys.QUIET_HOURS_END] = endHour.coerceIn(0, 23) }
    }

    fun isInQuietHours(): Boolean {
        // 同步版本，供 Service 快速判断
        val prefs = runCatching {
            kotlinx.coroutines.runBlocking { dataStore.data.first() }
        }.getOrNull() ?: return false
        val enabled = prefs[ProactiveKeys.QUIET_HOURS_ENABLED] ?: false
        if (!enabled) return false
        val start = prefs[ProactiveKeys.QUIET_HOURS_START] ?: 22
        val end = prefs[ProactiveKeys.QUIET_HOURS_END] ?: 8
        val now = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return if (start < end) now in start until end else now >= start || now < end
    }

    // ==================== 各触发类型阈值（规格书 P5） ====================

    /** 久坐阈值（小时），默认2，范围1-4 */
    val sedentaryThresholdHours: Flow<Int> = dataStore.data
        .map { prefs -> prefs[ProactiveKeys.SEDENTARY_THRESHOLD_HOURS] ?: 2 }

    suspend fun setSedentaryThresholdHours(hours: Int) {
        dataStore.edit { it[ProactiveKeys.SEDENTARY_THRESHOLD_HOURS] = hours.coerceIn(1, 4) }
    }

    /** 心率下限（bpm），默认45，范围35-60 */
    val heartRateLower: Flow<Int> = dataStore.data
        .map { prefs -> prefs[ProactiveKeys.HEART_RATE_LOWER] ?: 45 }

    suspend fun setHeartRateLower(bpm: Int) {
        dataStore.edit { it[ProactiveKeys.HEART_RATE_LOWER] = bpm.coerceIn(35, 60) }
    }

    /** 心率上限（bpm），默认100，范围80-130 */
    val heartRateUpper: Flow<Int> = dataStore.data
        .map { prefs -> prefs[ProactiveKeys.HEART_RATE_UPPER] ?: 100 }

    suspend fun setHeartRateUpper(bpm: Int) {
        dataStore.edit { it[ProactiveKeys.HEART_RATE_UPPER] = bpm.coerceIn(80, 130) }
    }

    /** 压力指数阈值（0-100整数表示0.00-1.00），默认70（即0.70） */
    val stressIndexThreshold: Flow<Int> = dataStore.data
        .map { prefs -> prefs[ProactiveKeys.STRESS_INDEX_THRESHOLD] ?: 70 }

    suspend fun setStressIndexThreshold(value: Int) {
        dataStore.edit { it[ProactiveKeys.STRESS_INDEX_THRESHOLD] = value.coerceIn(30, 95) }
    }

    /** 降水概率阈值（%），默认60 */
    val rainProbabilityThreshold: Flow<Int> = dataStore.data
        .map { prefs -> prefs[ProactiveKeys.RAIN_PROBABILITY_THRESHOLD] ?: 60 }

    suspend fun setRainProbabilityThreshold(prob: Int) {
        dataStore.edit { it[ProactiveKeys.RAIN_PROBABILITY_THRESHOLD] = prob.coerceIn(30, 90) }
    }

    /** 暗光阈值（lux），默认10 */
    val lowLightLuxThreshold: Flow<Int> = dataStore.data
        .map { prefs -> prefs[ProactiveKeys.LOW_LIGHT_LUX_THRESHOLD] ?: 10 }

    suspend fun setLowLightLuxThreshold(lux: Int) {
        dataStore.edit { it[ProactiveKeys.LOW_LIGHT_LUX_THRESHOLD] = lux.coerceIn(5, 50) }
    }

    private suspend fun getCurrentJson(key: Preferences.Key<String>): String {
        var result = ""
        dataStore.data.collect { prefs ->
            result = prefs[key] ?: ""
        }
        return result
    }

    suspend fun isTriggerTypeEnabled(type: TriggerType): Boolean {
        val enabled = enabledTriggerTypes
        var result = false
        enabled.collect { set ->
            result = set.contains(type)
        }
        return result
    }

    // ==================== Service 辅助方法（ProactiveCareService 调用） ====================

    /**
     * 点击通知后是否自动打开聊天页面（默认开启）
     */
    val autoLaunchChat: Flow<Boolean> = dataStore.data
        .map { prefs -> prefs[ProactiveKeys.AUTO_LAUNCH_CHAT] ?: true }

    suspend fun setAutoLaunchChat(enable: Boolean) {
        dataStore.edit { it[ProactiveKeys.AUTO_LAUNCH_CHAT] = enable }
    }

    /**
     * 一次性获取所有配置的快照（Service 层方便读取）
     */
    data class ConfigSnapshot(
        val enabled: Boolean,
        val cooldownMinutes: Int,
        val dailyLimit: Int,
        val autoLaunchChat: Boolean,
        val customReminders: List<CustomReminder>,
        val memorialDays: List<MemoryEvent>,
        val quietHoursEnabled: Boolean = false,
        val quietHoursStart: Int = 22,
        val quietHoursEnd: Int = 8
    )

    suspend fun getConfig(): ConfigSnapshot {
        val quintFlow = kotlinx.coroutines.flow.combine(
            enabled,
            cooldownMinutes,
            dailyLimit,
            autoLaunchChat,
            customReminders
        ) { e1, e2, e3, e4, e5 -> Quint(e1, e2, e3, e4, e5) }
        val hexFlow = kotlinx.coroutines.flow.combine(
            quintFlow,
            memoryEvents,
            quietHoursEnabled,
            quietHoursStart,
            quietHoursEnd
        ) { q, a6, qhEnabled, qhStart, qhEnd ->
            Hex(q.first, q.second, q.third, q.fourth, q.fifth, a6, qhEnabled, qhStart, qhEnd)
        }
        return hexFlow.map { (a1, a2, a3, a4, a5, a6, a7, a8, a9) ->
            ConfigSnapshot(
                enabled = a1,
                cooldownMinutes = a2,
                dailyLimit = a3,
                autoLaunchChat = a4,
                customReminders = a5,
                memorialDays = a6,
                quietHoursEnabled = a7,
                quietHoursStart = a8,
                quietHoursEnd = a9
            )
        }.first()
    }

    /**
     * 便捷获取自定义提醒列表（给 Service/UI 用）
     */
    suspend fun getCustomReminders(): List<CustomReminder> = customReminders.first()

    /**
     * 便捷获取生日/纪念日等记忆事件
     */
    suspend fun getMemorialDays(): List<MemoryEvent> = memoryEvents.first()

    // ==================== ViewModel 兼容方法 ====================

    suspend fun setTriggerEnabled(triggerType: TriggerType, enabled: Boolean) {
        val current = enabledTriggerTypes.first().toMutableSet()
        if (enabled) current.add(triggerType) else current.remove(triggerType)
        setEnabledTriggerTypes(current)
    }

    suspend fun setCustomReminders(reminders: List<CustomReminder>) {
        dataStore.edit { it[ProactiveKeys.CUSTOM_REMINDERS_JSON] = gson.toJson(reminders) }
    }

    suspend fun addMemorialDay(day: MemoryEvent) {
        addMemoryEvent(day)
    }

    suspend fun removeMemorialDay(dayId: String) {
        removeMemoryEvent(dayId)
    }

    suspend fun setMemorialDays(days: List<MemoryEvent>) {
        dataStore.edit { it[ProactiveKeys.MEMORY_EVENTS_JSON] = gson.toJson(days) }
    }

    suspend fun resetToDefault() {
        dataStore.edit { it.clear() }
    }

    // ==================== 组合 Flow（必须放在所有依赖 Flow 之后） ====================

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val configFlow: Flow<ProactiveConfigState> = kotlinx.coroutines.flow.combine(
        enabled,
        cooldownMinutes,
        dailyLimit,
        enabledTriggerTypes,
        generationStrategy,
        generatorModelType,
        sedentaryThresholdHours,
        heartRateLower,
        heartRateUpper,
        stressIndexThreshold,
        rainProbabilityThreshold,
        lowLightLuxThreshold,
        quietHoursEnabled,
        quietHoursStart,
        quietHoursEnd
    ) { values: Array<Any?> ->
        @Suppress("UNCHECKED_CAST")
        val qhStartEncoded = (values[13] as Int) * 100
        val qhEndEncoded = (values[14] as Int) * 100
        ProactiveConfigState(
            enabled = values[0] as Boolean,
            cooldownMs = (values[1] as Int) * 60_000L,
            dailyLimit = values[2] as Int,
            cooldownMinutes = values[1] as Int,
            enabledTriggerTypes = values[3] as Set<TriggerType>,
            generationStrategy = values[4] as GenerationStrategy,
            generatorModelType = values[5] as String,
            sedentaryThresholdMinutes = (values[6] as Int) * 60,
            heartRateLower = values[7] as Int,
            heartRateUpper = values[8] as Int,
            stressIndexThreshold = values[9] as Int,
            rainProbabilityThreshold = values[10] as Int,
            lowLightLuxThreshold = values[11] as Int,
            quietHoursEnabled = values[12] as Boolean,
            quietHoursStart = qhStartEncoded,
            quietHoursStartHour = qhStartEncoded / 100,
            quietHoursStartMin = qhStartEncoded % 100,
            quietHoursEnd = qhEndEncoded,
            quietHoursEndHour = qhEndEncoded / 100,
            quietHoursEndMin = qhEndEncoded % 100
        )
    }
}

private data class Quint<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)
private data class Hex<A, B, C, D, E, F, G, H, I>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E, val sixth: F, val seventh: G, val eighth: H, val ninth: I)
