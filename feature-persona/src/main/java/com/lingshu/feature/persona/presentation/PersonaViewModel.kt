package com.lingshu.feature.persona.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lingshu.feature.persona.domain.IPersonaService
import com.lingshu.feature.persona.domain.Persona
import com.lingshu.feature.persona.domain.PersonaSnapshot
import com.lingshu.feature.persona.domain.TraitType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PersonaViewModel @Inject constructor(
    private val personaService: IPersonaService
) : ViewModel() {

    val currentPersona: StateFlow<Persona> = personaService.observeCurrentPersona()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Persona.default())

    private val _history = MutableStateFlow<List<PersonaSnapshot>>(emptyList())
    val history: StateFlow<List<PersonaSnapshot>> = _history.asStateFlow()

    private val _isResetting = MutableStateFlow(false)
    val isResetting: StateFlow<Boolean> = _isResetting.asStateFlow()

    private val _generatedPrompt = MutableStateFlow("")
    val generatedPrompt: StateFlow<String> = _generatedPrompt.asStateFlow()

    fun loadHistory() {
        viewModelScope.launch {
            _history.value = personaService.getPersonaHistory()
        }
    }

    fun updateTrait(trait: TraitType, delta: Float) {
        viewModelScope.launch {
            personaService.updateTrait(trait, delta)
        }
    }

    fun resetPersona() {
        viewModelScope.launch {
            _isResetting.value = true
            personaService.resetToDefault()
            _history.value = emptyList()
            _isResetting.value = false
        }
    }

    fun generatePrompt() {
        viewModelScope.launch {
            _generatedPrompt.value = personaService.generateSystemPrompt()
        }
    }

    fun clearGeneratedPrompt() {
        _generatedPrompt.value = ""
    }
}
