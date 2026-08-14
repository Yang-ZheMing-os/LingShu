package com.lingshu.feature.persona.domain

import kotlinx.coroutines.flow.Flow

interface IPersonaService {
    fun observeCurrentPersona(): Flow<Persona>
    suspend fun getCurrentPersona(): Persona
    suspend fun updateTrait(trait: TraitType, delta: Float)
    suspend fun getPersonaHistory(): List<PersonaSnapshot>
    suspend fun resetToDefault()
    suspend fun generateSystemPrompt(): String
    suspend fun evolvePersona(userInput: String, aiResponse: String)
    suspend fun exportPersona(): String
    suspend fun importPersona(json: String): Boolean
}
