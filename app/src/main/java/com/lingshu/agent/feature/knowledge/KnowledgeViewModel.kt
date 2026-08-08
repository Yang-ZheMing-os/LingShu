package com.lingshu.agent.feature.knowledge

sealed class KnowledgeUiEvent

data class ShowToast

data class ShowError

data class DocumentAddedUi

data class DocumentDeletedUi

object AllCleared

data class RagAnswerFailedUi

data class RagNoContextUi

data class EnvironmentChecked

