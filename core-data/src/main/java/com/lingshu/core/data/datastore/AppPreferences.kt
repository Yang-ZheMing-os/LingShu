package com.lingshu.core.data.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "app_preferences")

class AppPreferences(private val context: Context) {

    val isFirstLaunch: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[IS_FIRST_LAUNCH] ?: true
        }

    val apiKey: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[API_KEY] ?: ""
        }

    val llmProvider: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[LLM_PROVIDER] ?: "OLLAMA"
        }

    val ollamaUrl: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[OLLAMA_URL] ?: "http://10.0.2.2:11434"
        }

    val ollamaModel: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[OLLAMA_MODEL] ?: "qwen2.5:0.5b"
        }

    val geminiApiKey: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[GEMINI_API_KEY] ?: ""
        }

    val personaWarmth: Flow<Float> = context.dataStore.data
        .map { preferences ->
            preferences[PERSONA_WARMTH] ?: 0.5f
        }

    val personaOpenness: Flow<Float> = context.dataStore.data
        .map { preferences ->
            preferences[PERSONA_OPENNESS] ?: 0.5f
        }

    val personaConscientiousness: Flow<Float> = context.dataStore.data
        .map { preferences ->
            preferences[PERSONA_CONSCIENTIOUSNESS] ?: 0.5f
        }

    val personaExtraversion: Flow<Float> = context.dataStore.data
        .map { preferences ->
            preferences[PERSONA_EXTRAVERSION] ?: 0.5f
        }

    val personaAgreeableness: Flow<Float> = context.dataStore.data
        .map { preferences ->
            preferences[PERSONA_AGREEABLENESS] ?: 0.5f
        }

    val personaNeuroticism: Flow<Float> = context.dataStore.data
        .map { preferences ->
            preferences[PERSONA_NEUROTICISM] ?: 0.3f
        }

    val personaAssertiveness: Flow<Float> = context.dataStore.data
        .map { preferences ->
            preferences[PERSONA_ASSERTIVENESS] ?: 0.4f
        }

    val personaHumor: Flow<Float> = context.dataStore.data
        .map { preferences ->
            preferences[PERSONA_HUMOR] ?: 0.4f
        }

    val personaFormality: Flow<Float> = context.dataStore.data
        .map { preferences ->
            preferences[PERSONA_FORMALITY] ?: 0.5f
        }

    suspend fun setFirstLaunch(isFirstLaunch: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[IS_FIRST_LAUNCH] = isFirstLaunch
        }
    }

    suspend fun setApiKey(apiKey: String) {
        context.dataStore.edit { preferences ->
            preferences[API_KEY] = apiKey
        }
    }

    suspend fun setLlmProvider(provider: String) {
        context.dataStore.edit { preferences ->
            preferences[LLM_PROVIDER] = provider
        }
    }

    suspend fun setOllamaUrl(url: String) {
        context.dataStore.edit { preferences ->
            preferences[OLLAMA_URL] = url
        }
    }

    suspend fun setOllamaModel(model: String) {
        context.dataStore.edit { preferences ->
            preferences[OLLAMA_MODEL] = model
        }
    }

    suspend fun setGeminiApiKey(key: String) {
        context.dataStore.edit { preferences ->
            preferences[GEMINI_API_KEY] = key
        }
    }

    suspend fun setPersonaTrait(key: androidx.datastore.preferences.core.Preferences.Key<Float>, value: Float) {
        context.dataStore.edit { preferences ->
            preferences[key] = value.coerceIn(0f, 1f)
        }
    }

    suspend fun resetPersona() {
        context.dataStore.edit { preferences ->
            preferences[PERSONA_WARMTH] = 0.5f
            preferences[PERSONA_OPENNESS] = 0.5f
            preferences[PERSONA_CONSCIENTIOUSNESS] = 0.5f
            preferences[PERSONA_EXTRAVERSION] = 0.5f
            preferences[PERSONA_AGREEABLENESS] = 0.5f
            preferences[PERSONA_NEUROTICISM] = 0.3f
            preferences[PERSONA_ASSERTIVENESS] = 0.4f
            preferences[PERSONA_HUMOR] = 0.4f
            preferences[PERSONA_FORMALITY] = 0.5f
        }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }

    companion object {
        private val IS_FIRST_LAUNCH = booleanPreferencesKey("is_first_launch")
        private val API_KEY = stringPreferencesKey("api_key")
        private val LLM_PROVIDER = stringPreferencesKey("llm_provider")
        private val OLLAMA_URL = stringPreferencesKey("ollama_url")
        private val OLLAMA_MODEL = stringPreferencesKey("ollama_model")
        private val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")

        val PERSONA_WARMTH = floatPreferencesKey("persona_warmth")
        val PERSONA_OPENNESS = floatPreferencesKey("persona_openness")
        val PERSONA_CONSCIENTIOUSNESS = floatPreferencesKey("persona_conscientiousness")
        val PERSONA_EXTRAVERSION = floatPreferencesKey("persona_extraversion")
        val PERSONA_AGREEABLENESS = floatPreferencesKey("persona_agreeableness")
        val PERSONA_NEUROTICISM = floatPreferencesKey("persona_neuroticism")
        val PERSONA_ASSERTIVENESS = floatPreferencesKey("persona_assertiveness")
        val PERSONA_HUMOR = floatPreferencesKey("persona_humor")
        val PERSONA_FORMALITY = floatPreferencesKey("persona_formality")
    }
}
