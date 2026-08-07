package com.lingshu.agent.core.model

data class BigFiveTraits(
    val openness: Double = 0.5,
    val conscientiousness: Double = 0.5,
    val extraversion: Double = 0.5,
    val agreeableness: Double = 0.5,
    val neuroticism: Double = 0.5
) {
    fun clamp(): BigFiveTraits {
        return BigFiveTraits(
            openness = openness.coerceIn(0.0, 1.0),
            conscientiousness = conscientiousness.coerceIn(0.0, 1.0),
            extraversion = extraversion.coerceIn(0.0, 1.0),
            agreeableness = agreeableness.coerceIn(0.0, 1.0),
            neuroticism = neuroticism.coerceIn(0.0, 1.0)
        )
    }

    fun adjust(delta: BigFiveTraits, rate: Double = 0.05): BigFiveTraits {
        return BigFiveTraits(
            openness = (openness + delta.openness * rate).coerceIn(0.0, 1.0),
            conscientiousness = (conscientiousness + delta.conscientiousness * rate).coerceIn(0.0, 1.0),
            extraversion = (extraversion + delta.extraversion * rate).coerceIn(0.0, 1.0),
            agreeableness = (agreeableness + delta.agreeableness * rate).coerceIn(0.0, 1.0),
            neuroticism = (neuroticism + delta.neuroticism * rate).coerceIn(0.0, 1.0)
        )
    }

    companion object {
        fun neutral() = BigFiveTraits(0.5, 0.5, 0.5, 0.5, 0.5)
        fun gentle() = BigFiveTraits(0.6, 0.7, 0.4, 0.8, 0.2)
        fun humorous() = BigFiveTraits(0.8, 0.5, 0.7, 0.6, 0.3)
        fun sharp() = BigFiveTraits(0.7, 0.9, 0.5, 0.3, 0.2)
        fun calm() = BigFiveTraits(0.5, 0.8, 0.3, 0.7, 0.1)
    }
}

data class Persona(
    val personaId: String = System.currentTimeMillis().toString(),
    val name: String = "灵枢",
    val avatar: String? = null,
    val systemPrompt: String = "",
    val traits: BigFiveTraits = BigFiveTraits.neutral(),
    val voiceId: String? = null,
    val temperature: Double = 0.7,
    val memory: List<String> = emptyList(),
    val openingLine: String? = null,
    val exampleDialogues: List<Pair<String, String>> = emptyList(),
    val tags: List<String> = emptyList(),
    val toneTags: List<String> = emptyList(),
    val rules: PersonaRules = PersonaRules(),
    val isActive: Boolean = false,
    val isSystem: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class PersonaRules(
    val canInitiateConversation: Boolean = true,
    val confirmBeforeExecute: Boolean = true,
    val canUseSensitiveOperations: Boolean = false,
    val canAccessInternet: Boolean = true
)
