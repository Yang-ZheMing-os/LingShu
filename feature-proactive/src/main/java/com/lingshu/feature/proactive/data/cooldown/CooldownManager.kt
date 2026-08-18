package com.lingshu.feature.proactive.data.cooldown

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lingshu.feature.proactive.domain.ProactiveConfig
import com.lingshu.feature.proactive.domain.QuietHours
import com.lingshu.feature.proactive.domain.TriggerType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Calendar

private val Context.proactiveDataStore by preferencesDataStore(name = "proactive_preferences")

class CooldownManager(private val context: Context) {

    val todayCount: Flow<Int> = context.proactiveDataStore.data
        .map { preferences ->
            val lastDay = preferences[LAST_DAY] ?: 0
            val today = getTodayKey()
            if (lastDay != today) 0 else preferences[TODAY_COUNT] ?: 0
        }

    val lastTriggerTime: Flow<Long> = context.proactiveDataStore.data
        .map { it[LAST_TRIGGER_TIME] ?: 0L }

    val lastTriggerType: Flow<TriggerType?> = context.proactiveDataStore.data
        .map {
            val name = it[LAST_TRIGGER_TYPE] ?: return@map null
            runCatching { TriggerType.valueOf(name) }.getOrNull()
        }

    suspend fun canTrigger(config: ProactiveConfig): Boolean {
        val prefs = context.proactiveDataStore.data.first()
        val lastDay = prefs[LAST_DAY] ?: 0
        val today = getTodayKey()

        val count = if (lastDay != today) 0 else prefs[TODAY_COUNT] ?: 0
        if (count >= config.maxPerDay) return false

        val lastTime = prefs[LAST_TRIGGER_TIME] ?: 0L
        val cooldownMs = config.cooldownMinutes * 60 * 1000L
        if (System.currentTimeMillis() - lastTime < cooldownMs) return false

        return true
    }

    suspend fun recordTrigger(triggerType: TriggerType) {
        val today = getTodayKey()
        context.proactiveDataStore.edit { preferences ->
            val lastDay = preferences[LAST_DAY] ?: 0
            val currentCount = if (lastDay != today) 0 else preferences[TODAY_COUNT] ?: 0
            preferences[LAST_DAY] = today
            preferences[TODAY_COUNT] = currentCount + 1
            preferences[LAST_TRIGGER_TIME] = System.currentTimeMillis()
            preferences[LAST_TRIGGER_TYPE] = triggerType.name
        }
    }

    suspend fun resetTodayCount() {
        context.proactiveDataStore.edit { preferences ->
            preferences[LAST_DAY] = getTodayKey()
            preferences[TODAY_COUNT] = 0
        }
    }

    // ===== ProactiveConfig 持久化（解决「用户打开开关、重启后没了」的根因） =====

    suspend fun saveConfig(config: ProactiveConfig) {
        context.proactiveDataStore.edit { p ->
            p[CFG_ENABLED] = config.enabled
            p[CFG_COOLDOWN_MIN] = config.cooldownMinutes
            p[CFG_MAX_PER_DAY] = config.maxPerDay
            p[CFG_QUIET_START_H] = config.quietHours.startHour
            p[CFG_QUIET_START_M] = config.quietHours.startMinute
            p[CFG_QUIET_END_H] = config.quietHours.endHour
            p[CFG_QUIET_END_M] = config.quietHours.endMinute
            p[CFG_RANDOM_PROB] = config.randomTriggerProbability
            p[CFG_QWEATHER_KEY] = config.qWeatherKey
            p[CFG_QWEATHER_LOC] = config.qWeatherLocation
            TriggerType.values().forEach { t ->
                p[stringPreferencesKey("cfg_trigger_${t.name}")] =
                    (config.triggers[t] ?: false).toString()
            }
        }
    }

    suspend fun loadConfig(): ProactiveConfig {
        val prefs = context.proactiveDataStore.data.first()
        val triggers = TriggerType.values().associateWith { t ->
            prefs[stringPreferencesKey("cfg_trigger_${t.name}")]?.toBooleanStrictOrNull() ?: true
        }
        val enabled = prefs[CFG_ENABLED] ?: false
        val cooldown = prefs[CFG_COOLDOWN_MIN] ?: ProactiveConfig().cooldownMinutes
        val maxPerDay = prefs[CFG_MAX_PER_DAY] ?: ProactiveConfig().maxPerDay
        val qStartH = prefs[CFG_QUIET_START_H] ?: QuietHours().startHour
        val qStartM = prefs[CFG_QUIET_START_M] ?: QuietHours().startMinute
        val qEndH = prefs[CFG_QUIET_END_H] ?: QuietHours().endHour
        val qEndM = prefs[CFG_QUIET_END_M] ?: QuietHours().endMinute
        val randProb = prefs[CFG_RANDOM_PROB] ?: ProactiveConfig().randomTriggerProbability
        val qwKey = prefs[CFG_QWEATHER_KEY] ?: ""
        val qwLoc = prefs[CFG_QWEATHER_LOC] ?: "auto_ip"
        return ProactiveConfig(
            enabled = enabled,
            triggers = triggers,
            cooldownMinutes = cooldown,
            maxPerDay = maxPerDay,
            quietHours = QuietHours(qStartH, qStartM, qEndH, qEndM),
            randomTriggerProbability = randProb,
            qWeatherKey = qwKey,
            qWeatherLocation = qwLoc
        )
    }

    private fun getTodayKey(): Int {
        val calendar = Calendar.getInstance()
        return calendar.get(Calendar.YEAR) * 10000 +
               (calendar.get(Calendar.MONTH) + 1) * 100 +
               calendar.get(Calendar.DAY_OF_MONTH)
    }

    companion object {
        private val LAST_DAY = intPreferencesKey("last_day")
        private val TODAY_COUNT = intPreferencesKey("today_count")
        private val LAST_TRIGGER_TIME = longPreferencesKey("last_trigger_time")
        private val LAST_TRIGGER_TYPE = stringPreferencesKey("last_trigger_type")

        private val CFG_ENABLED = booleanPreferencesKey("cfg_enabled")
        private val CFG_COOLDOWN_MIN = intPreferencesKey("cfg_cooldown_min")
        private val CFG_MAX_PER_DAY = intPreferencesKey("cfg_max_per_day")
        private val CFG_QUIET_START_H = intPreferencesKey("cfg_quiet_start_h")
        private val CFG_QUIET_START_M = intPreferencesKey("cfg_quiet_start_m")
        private val CFG_QUIET_END_H = intPreferencesKey("cfg_quiet_end_h")
        private val CFG_QUIET_END_M = intPreferencesKey("cfg_quiet_end_m")
        private val CFG_RANDOM_PROB = floatPreferencesKey("cfg_random_prob")
        private val CFG_QWEATHER_KEY = stringPreferencesKey("cfg_qweather_key")
        private val CFG_QWEATHER_LOC = stringPreferencesKey("cfg_qweather_loc")
    }
}
