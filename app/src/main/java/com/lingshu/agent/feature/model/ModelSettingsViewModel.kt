package com.lingshu.agent.feature.model

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

data class ModelSettingsUiState

data class ModelItemUiState

@HiltViewModel
class ModelSettingsViewModel @Inject constructor() : ViewModel()

