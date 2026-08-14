package com.lingshu.feature.persona.data

import com.lingshu.core.common.log.LingShuLog
import com.lingshu.core.data.datastore.AppPreferences
import com.lingshu.feature.persona.domain.IPersonaService
import com.lingshu.feature.persona.domain.Persona
import com.lingshu.feature.persona.domain.PersonaSnapshot
import com.lingshu.feature.persona.domain.TraitType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class PersonaServiceImpl @Inject constructor(
    private val appPreferences: AppPreferences,
    private val sentimentAnalyzer: SentimentAnalyzer,
    private val personaPromptGenerator: PersonaPromptGenerator
) : IPersonaService {

    private val _personaHistory = MutableStateFlow<List<PersonaSnapshot>>(emptyList())
    private val maxHistorySize = 50
    private val feedbackCounts = mutableMapOf<TraitType, Int>()

    override fun observeCurrentPersona(): Flow<Persona> {
        return combine(
            appPreferences.personaWarmth,
            appPreferences.personaOpenness,
            appPreferences.personaConscientiousness,
            appPreferences.personaExtraversion,
            appPreferences.personaAgreeableness,
            appPreferences.personaNeuroticism,
            appPreferences.personaAssertiveness,
            appPreferences.personaHumor,
            appPreferences.personaFormality
        ) { values ->
            Persona(
                warmth = values[0],
                openness = values[1],
                conscientiousness = values[2],
                extraversion = values[3],
                agreeableness = values[4],
                neuroticism = values[5],
                assertiveness = values[6],
                humor = values[7],
                formality = values[8]
            )
        }
    }

    override suspend fun getCurrentPersona(): Persona {
        return observeCurrentPersona().first()
    }

    override suspend fun updateTrait(trait: TraitType, delta: Float) {
        val clampedDelta = delta.coerceIn(-0.05f, 0.05f)
        val currentPersona = getCurrentPersona()
        val currentValue = currentPersona.getTrait(trait)
        val newValue = (currentValue + clampedDelta).coerceIn(0f, 1f)

        val key = getPreferenceKey(trait)
        appPreferences.setPersonaTrait(key, newValue)

        LingShuLog.d(TAG, "更新人格特质: $trait, $currentValue -> $newValue (delta: $clampedDelta)")
    }

    override suspend fun getPersonaHistory(): List<PersonaSnapshot> {
        return _personaHistory.value
    }

    override suspend fun resetToDefault() {
        appPreferences.resetPersona()
        _personaHistory.value = emptyList()
        LingShuLog.i(TAG, "人格已重置为默认值")
    }

    override suspend fun generateSystemPrompt(): String {
        val persona = getCurrentPersona()
        return personaPromptGenerator.generate(persona)
    }

    override suspend fun evolvePersona(userInput: String, aiResponse: String) {
        try {
            val analysis = sentimentAnalyzer.analyze(userInput, aiResponse)
            val currentPersona = getCurrentPersona()
            val changes = mutableMapOf<TraitType, Float>()

            when (analysis.sentiment) {
                Sentiment.POSITIVE -> {
                    changes[TraitType.WARMTH] = (changes[TraitType.WARMTH] ?: 0f) + 0.02f
                    changes[TraitType.AGREEABLENESS] = (changes[TraitType.AGREEABLENESS] ?: 0f) + 0.02f
                }
                Sentiment.NEGATIVE -> {
                    changes[TraitType.NEUROTICISM] = (changes[TraitType.NEUROTICISM] ?: 0f) + 0.02f
                    changes[TraitType.FORMALITY] = (changes[TraitType.FORMALITY] ?: 0f) + 0.02f
                }
                Sentiment.NEUTRAL -> {
                }
            }

            if (analysis.expressesLiking) {
                changes[TraitType.WARMTH] = (changes[TraitType.WARMTH] ?: 0f) + 0.05f
            }

            if (analysis.expressesDisliking) {
                changes[TraitType.AGREEABLENESS] = (changes[TraitType.AGREEABLENESS] ?: 0f) - 0.03f
            }

            when (analysis.tone) {
                Tone.CASUAL -> {
                    changes[TraitType.HUMOR] = (changes[TraitType.HUMOR] ?: 0f) + 0.02f
                    changes[TraitType.FORMALITY] = (changes[TraitType.FORMALITY] ?: 0f) - 0.02f
                }
                Tone.FORMAL -> {
                    changes[TraitType.FORMALITY] = (changes[TraitType.FORMALITY] ?: 0f) + 0.02f
                    changes[TraitType.HUMOR] = (changes[TraitType.HUMOR] ?: 0f) - 0.02f
                }
                Tone.NEUTRAL -> {
                }
            }

            changes.forEach { (trait, delta) ->
                val count = (feedbackCounts[trait] ?: 0) + 1
                feedbackCounts[trait] = count
                val decayFactor = 1f / kotlin.math.sqrt(count.toFloat())
                val adjustedDelta = delta * decayFactor
                updateTrait(trait, adjustedDelta)
            }

            val newPersona = getCurrentPersona()
            addToHistory(newPersona, buildEvolveReason(analysis))

            LingShuLog.d(TAG, "人格演化完成，变化特质数: ${changes.size}")
        } catch (e: Exception) {
            LingShuLog.e(TAG, "人格演化失败", e)
        }
    }

    override suspend fun exportPersona(): String {
        val persona = getCurrentPersona()
        val json = org.json.JSONObject().apply {
            put("version", 1)
            put("timestamp", System.currentTimeMillis())
            put("persona", org.json.JSONObject().apply {
                put("warmth", persona.warmth)
                put("openness", persona.openness)
                put("conscientiousness", persona.conscientiousness)
                put("extraversion", persona.extraversion)
                put("agreeableness", persona.agreeableness)
                put("neuroticism", persona.neuroticism)
                put("assertiveness", persona.assertiveness)
                put("humor", persona.humor)
                put("formality", persona.formality)
            })
        }
        return json.toString(2)
    }

    override suspend fun importPersona(json: String): Boolean {
        return try {
            val obj = org.json.JSONObject(json)
            val personaObj = obj.getJSONObject("persona")
            val traits = listOf(
                TraitType.WARMTH to personaObj.optDouble("warmth", 0.5).toFloat(),
                TraitType.OPENNESS to personaObj.optDouble("openness", 0.5).toFloat(),
                TraitType.CONSCIENTIOUSNESS to personaObj.optDouble("conscientiousness", 0.5).toFloat(),
                TraitType.EXTRAVERSION to personaObj.optDouble("extraversion", 0.5).toFloat(),
                TraitType.AGREEABLENESS to personaObj.optDouble("agreeableness", 0.5).toFloat(),
                TraitType.NEUROTICISM to personaObj.optDouble("neuroticism", 0.3).toFloat(),
                TraitType.ASSERTIVENESS to personaObj.optDouble("assertiveness", 0.4).toFloat(),
                TraitType.HUMOR to personaObj.optDouble("humor", 0.4).toFloat(),
                TraitType.FORMALITY to personaObj.optDouble("formality", 0.5).toFloat()
            )
            val current = getCurrentPersona()
            traits.forEach { (trait, targetValue) ->
                val delta = targetValue - current.getTrait(trait)
                val key = getPreferenceKey(trait)
                appPreferences.setPersonaTrait(key, targetValue.coerceIn(0f, 1f))
            }
            LingShuLog.i(TAG, "Persona imported successfully")
            true
        } catch (e: Exception) {
            LingShuLog.e(TAG, "Failed to import persona: ${e.message}", e)
            false
        }
    }

    private suspend fun addToHistory(persona: Persona, reason: String) {
        val snapshot = PersonaSnapshot(
            persona = persona,
            timestamp = System.currentTimeMillis(),
            reason = reason
        )
        val currentList = _personaHistory.value.toMutableList()
        currentList.add(0, snapshot)
        if (currentList.size > maxHistorySize) {
            currentList.removeAt(currentList.size - 1)
        }
        _personaHistory.value = currentList
    }

    private fun buildEvolveReason(analysis: SentimentAnalysisResult): String {
        val reasons = mutableListOf<String>()
        when (analysis.sentiment) {
            Sentiment.POSITIVE -> reasons.add("正面情感")
            Sentiment.NEGATIVE -> reasons.add("负面情感")
            Sentiment.NEUTRAL -> reasons.add("中性情感")
        }
        when (analysis.tone) {
            Tone.CASUAL -> reasons.add("轻松语气")
            Tone.FORMAL -> reasons.add("正式语气")
            Tone.NEUTRAL -> reasons.add("中性语气")
        }
        if (analysis.expressesLiking) reasons.add("表达喜爱")
        if (analysis.expressesDisliking) reasons.add("表达不喜欢")
        return reasons.joinToString(", ")
    }

    private fun getPreferenceKey(trait: TraitType): androidx.datastore.preferences.core.Preferences.Key<Float> {
        return when (trait) {
            TraitType.WARMTH -> AppPreferences.PERSONA_WARMTH
            TraitType.OPENNESS -> AppPreferences.PERSONA_OPENNESS
            TraitType.CONSCIENTIOUSNESS -> AppPreferences.PERSONA_CONSCIENTIOUSNESS
            TraitType.EXTRAVERSION -> AppPreferences.PERSONA_EXTRAVERSION
            TraitType.AGREEABLENESS -> AppPreferences.PERSONA_AGREEABLENESS
            TraitType.NEUROTICISM -> AppPreferences.PERSONA_NEUROTICISM
            TraitType.ASSERTIVENESS -> AppPreferences.PERSONA_ASSERTIVENESS
            TraitType.HUMOR -> AppPreferences.PERSONA_HUMOR
            TraitType.FORMALITY -> AppPreferences.PERSONA_FORMALITY
        }
    }

    companion object {
        private const val TAG = "PersonaService"
    }
}
