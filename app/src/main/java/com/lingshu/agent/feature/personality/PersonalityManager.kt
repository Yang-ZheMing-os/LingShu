package com.lingshu.agent.feature.personality

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PersonalityManager @Inject constructor() {
    private val _currentPersonality = MutableStateFlow(PersonalityState())
    val currentPersonality: StateFlow<PersonalityState> = _currentPersonality

    fun buildPersonalityPrompt(): String = ""
    fun updatePersonality(state: PersonalityState) {}
    fun resetToDefault() {}
    suspend fun analyzeAndAdjust(messages: List<Any>) {}
}
