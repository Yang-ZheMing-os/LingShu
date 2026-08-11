package com.lingshu.feature.persona.domain

data class Persona(
    val warmth: Float = 0.5f,
    val openness: Float = 0.5f,
    val conscientiousness: Float = 0.5f,
    val extraversion: Float = 0.5f,
    val agreeableness: Float = 0.5f,
    val neuroticism: Float = 0.3f,
    val assertiveness: Float = 0.4f,
    val humor: Float = 0.4f,
    val formality: Float = 0.5f
) {
    fun getTrait(traitType: TraitType): Float {
        return when (traitType) {
            TraitType.WARMTH -> warmth
            TraitType.OPENNESS -> openness
            TraitType.CONSCIENTIOUSNESS -> conscientiousness
            TraitType.EXTRAVERSION -> extraversion
            TraitType.AGREEABLENESS -> agreeableness
            TraitType.NEUROTICISM -> neuroticism
            TraitType.ASSERTIVENESS -> assertiveness
            TraitType.HUMOR -> humor
            TraitType.FORMALITY -> formality
        }
    }

    fun copyWithTrait(traitType: TraitType, value: Float): Persona {
        val clampedValue = value.coerceIn(0f, 1f)
        return when (traitType) {
            TraitType.WARMTH -> copy(warmth = clampedValue)
            TraitType.OPENNESS -> copy(openness = clampedValue)
            TraitType.CONSCIENTIOUSNESS -> copy(conscientiousness = clampedValue)
            TraitType.EXTRAVERSION -> copy(extraversion = clampedValue)
            TraitType.AGREEABLENESS -> copy(agreeableness = clampedValue)
            TraitType.NEUROTICISM -> copy(neuroticism = clampedValue)
            TraitType.ASSERTIVENESS -> copy(assertiveness = clampedValue)
            TraitType.HUMOR -> copy(humor = clampedValue)
            TraitType.FORMALITY -> copy(formality = clampedValue)
        }
    }

    fun toMap(): Map<TraitType, Float> {
        return mapOf(
            TraitType.WARMTH to warmth,
            TraitType.OPENNESS to openness,
            TraitType.CONSCIENTIOUSNESS to conscientiousness,
            TraitType.EXTRAVERSION to extraversion,
            TraitType.AGREEABLENESS to agreeableness,
            TraitType.NEUROTICISM to neuroticism,
            TraitType.ASSERTIVENESS to assertiveness,
            TraitType.HUMOR to humor,
            TraitType.FORMALITY to formality
        )
    }

    companion object {
        fun default(): Persona {
            return Persona(
                warmth = 0.5f,
                openness = 0.5f,
                conscientiousness = 0.5f,
                extraversion = 0.5f,
                agreeableness = 0.5f,
                neuroticism = 0.3f,
                assertiveness = 0.4f,
                humor = 0.4f,
                formality = 0.5f
            )
        }
    }
}
