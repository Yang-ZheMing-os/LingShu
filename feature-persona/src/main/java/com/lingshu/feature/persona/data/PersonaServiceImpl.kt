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
        ) { warmth, openness, conscientiousness, extraversion, agreeableness,
            neuroticism, assertiveness, humor, formality ->
            Persona(
                warmth = warmth,
                openness = openness,
                conscientiousness = conscientiousness,
                extraversion = extraversion,
                agreeableness = agreeableness,
                neuroticism = neuroticism,
                assertiveness = assertiveness,
                humor = humor,
                formality = formality
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
                updateTrait(trait, delta)
            }

            val newPersona = getCurrentPersona()
            addToHistory(newPersona, buildEvolveReason(analysis))

            LingShuLog.d(TAG, "人格演化完成，变化特质数: ${changes.size}")
        } catch (e: Exception) {
            LingShuLog.e(TAG, "人格演化失败", e)
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
