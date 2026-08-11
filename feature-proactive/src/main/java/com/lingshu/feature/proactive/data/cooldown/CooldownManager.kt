package com.lingshu.feature.proactive.data.cooldown

import android.content.Context
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.lingshu.feature.proactive.domain.ProactiveConfig
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
        val lastDay = context.proactiveDataStore.data.first()[LAST_DAY] ?: 0
        val today = getTodayKey()
        
        val count = if (lastDay != today) 0 else context.proactiveDataStore.data.first()[TODAY_COUNT] ?: 0
        if (count >= config.maxPerDay) return false

        val lastTime = context.proactiveDataStore.data.first()[LAST_TRIGGER_TIME] ?: 0L
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
        private val LAST_TRIGGER_TYPE = androidx.datastore.preferences.core.stringPreferencesKey("last_trigger_type")
    }
}
