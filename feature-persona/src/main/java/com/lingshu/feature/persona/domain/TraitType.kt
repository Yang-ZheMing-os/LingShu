package com.lingshu.feature.persona.domain

enum class TraitType {
    WARMTH,
    OPENNESS,
    CONSCIENTIOUSNESS,
    EXTRAVERSION,
    AGREEABLENESS,
    NEUROTICISM,
    ASSERTIVENESS,
    HUMOR,
    FORMALITY;

    val displayName: String
        get() = when (this) {
            WARMTH -> "温暖度"
            OPENNESS -> "开放性"
            CONSCIENTIOUSNESS -> "尽责性"
            EXTRAVERSION -> "外向性"
            AGREEABLENESS -> "宜人性"
            NEUROTICISM -> "神经质"
            ASSERTIVENESS -> "果断性"
            HUMOR -> "幽默感"
            FORMALITY -> "正式度"
        }
}
