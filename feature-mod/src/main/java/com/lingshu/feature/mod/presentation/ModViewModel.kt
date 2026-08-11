package com.lingshu.feature.mod.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lingshu.core.common.error.Result
import com.lingshu.core.common.state.UiState
import com.lingshu.feature.mod.domain.IModService
import com.lingshu.feature.mod.domain.Mod
import com.lingshu.feature.mod.domain.ModInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class ModViewModel @Inject constructor(
    private val modService: IModService
) : ViewModel() {

    private val _installedMods = MutableStateFlow<List<Mod>>(emptyList())
    val installedMods: StateFlow<List<Mod>> = _installedMods.asStateFlow()

    private val _availableMods = MutableStateFlow<List<ModInfo>>(emptyList())
    val availableMods: StateFlow<List<ModInfo>> = _availableMods.asStateFlow()

    private val _selectedTab = MutableStateFlow(ModTab.INSTALLED)
    val selectedTab: StateFlow<ModTab> = _selectedTab.asStateFlow()

    private val _installState = MutableStateFlow<UiState<Mod>>(UiState.Idle)
    val installState: StateFlow<UiState<Mod>> = _installState.asStateFlow()

    private val _downloadState = MutableStateFlow<UiState<File>>(UiState.Idle)
    val downloadState: StateFlow<UiState<File>> = _downloadState.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadInstalledMods()
    }

    private fun loadInstalledMods() {
        _installedMods.value = modService.listMods()
    }

    fun selectTab(tab: ModTab) {
        _selectedTab.value = tab
        if (tab == ModTab.STORE && _availableMods.value.isEmpty()) {
            fetchModList()
        }
    }

    fun fetchModList() {
        viewModelScope.launch {
            _isLoading.value = true
            val result = modService.fetchModList()
            when (result) {
                is Result.Success -> {
                    _availableMods.value = result.data
                }
                is Result.Error -> {
                    // Handle error
                }
            }
            _isLoading.value = false
        }
    }

    fun installMod(file: File) {
        viewModelScope.launch {
            _installState.value = UiState.Loading
            val result = modService.installMod(file)
            when (result) {
                is Result.Success -> {
                    _installState.value = UiState.Success(result.data)
                    loadInstalledMods()
                }
                is Result.Error -> {
                    _installState.value = UiState.Error(result.code, result.message)
                }
            }
        }
    }

    fun enableMod(modId: String) {
        viewModelScope.launch {
            val result = modService.enableMod(modId)
            if (result.isSuccess) {
                loadInstalledMods()
            }
        }
    }

    fun disableMod(modId: String) {
        viewModelScope.launch {
            val result = modService.disableMod(modId)
            if (result.isSuccess) {
                loadInstalledMods()
            }
        }
    }

    fun uninstallMod(modId: String) {
        viewModelScope.launch {
            val result = modService.uninstallMod(modId)
            if (result.isSuccess) {
                loadInstalledMods()
            }
        }
    }

    fun downloadAndInstallMod(modId: String) {
        viewModelScope.launch {
            _downloadState.value = UiState.Loading
            val downloadResult = modService.downloadMod(modId)
            when (downloadResult) {
                is Result.Success -> {
                    _downloadState.value = UiState.Success(downloadResult.data)
                    installMod(downloadResult.data)
                }
                is Result.Error -> {
                    _downloadState.value = UiState.Error(downloadResult.code, downloadResult.message)
                }
            }
        }
    }

    fun resetInstallState() {
        _installState.value = UiState.Idle
    }

    fun resetDownloadState() {
        _downloadState.value = UiState.Idle
    }
}

enum class ModTab {
    INSTALLED,
    STORE
}
