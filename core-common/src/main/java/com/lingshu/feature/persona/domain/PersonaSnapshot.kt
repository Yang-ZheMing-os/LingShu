package com.lingshu.feature.persona.domain

data class PersonaSnapshot(
    val persona: Persona,
    val timestamp: Long = System.currentTimeMillis(),
    val reason: String = ""
)
