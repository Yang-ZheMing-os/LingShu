package com.lingshu.agent.feature.persona

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

enum class PersonaFilterMode

enum class TraitType

enum class RuleType

sealed class PersonaEvent

data class PersonaSaved

data class PersonaDeleted

data class PersonaActivated

data class PersonasImported

data class MemoriesInjected

data class OperationFailed

data class PersonaListUiState

data class PersonaEditorUiState

data class ActivePersonaUiState

@HiltViewModel
class PersonaViewModel @Inject constructor() : ViewModel()

