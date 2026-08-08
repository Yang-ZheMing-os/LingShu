package com.lingshu.agent.feature.persona

import com.lingshu.agent.core.model.Persona
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PersonaManager @Inject constructor() {
    suspend fun getActivePersonaSuspend(): Persona? = null
    fun buildSystemPrompt(persona: Persona, context: String): String = ""
    fun getActivePersona(): Persona? = null
}
