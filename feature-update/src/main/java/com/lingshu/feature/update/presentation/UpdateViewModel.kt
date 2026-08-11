package com.lingshu.feature.update.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lingshu.core.common.error.Result
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.feature.update.data.ErrorReportManager
import com.lingshu.feature.update.domain.IUpdateService
import com.lingshu.feature.update.domain.UpdateInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val updateService: IUpdateService,
    private val errorReportManager: ErrorReportManager
) : ViewModel() {

    companion object {
        private const val TAG = "UpdateViewModel"
    }

    private val _uiState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress: StateFlow<Int> = _downloadProgress.asStateFlow()

    private val _showUpdateDialog = MutableStateFlow(false)
    val showUpdateDialog: StateFlow<Boolean> = _showUpdateDialog.asStateFlow()

    private var currentUpdateInfo: UpdateInfo? = null
    private var downloadedFile: File? = null

    fun checkForUpdate(showDialog: Boolean = true) {
        viewModelScope.launch {
            _uiState.value = UpdateUiState.Checking
            when (val result = updateService.checkForUpdate()) {
                is Result.Success -> {
                    currentUpdateInfo = result.data
                    _uiState.value = UpdateUiState.UpdateAvailable(result.data)
                    if (showDialog) {
                        _showUpdateDialog.value = true
                    }
                }
                is Result.Error -> {
                    _uiState.value = UpdateUiState.Error(result.message)
                    LingShuLog.e(TAG, "检查更新失败: ${result.message}")
                }
            }
        }
    }

    fun startDownload() {
        val updateInfo = currentUpdateInfo ?: return

        viewModelScope.launch {
            _uiState.value = UpdateUiState.Downloading
            when (val result = updateService.downloadUpdate(updateInfo) { progress ->
                _downloadProgress.value = progress
            }) {
                is Result.Success -> {
                    downloadedFile = result.data
                    verifyAndInstall(result.data)
                }
                is Result.Error -> {
                    _uiState.value = UpdateUiState.Error(result.message)
                    LingShuLog.e(TAG, "下载失败: ${result.message}")
                }
            }
        }
    }

    private suspend fun verifyAndInstall(apkFile: File) {
        val updateInfo = currentUpdateInfo ?: return

        if (updateInfo.md5.isNotEmpty()) {
            val isValid = updateService.verifyMd5(apkFile, updateInfo.md5)
            if (!isValid) {
                _uiState.value = UpdateUiState.Error("MD5 校验失败，文件可能已损坏")
                return
            }
        }

        _uiState.value = UpdateUiState.DownloadComplete(apkFile)
    }

    fun installUpdate() {
        val file = downloadedFile ?: return

        viewModelScope.launch {
            when (val result = updateService.installUpdate(file)) {
                is Result.Success -> {
                    _uiState.value = UpdateUiState.Installing
                }
                is Result.Error -> {
                    _uiState.value = UpdateUiState.Error(result.message)
                    LingShuLog.e(TAG, "安装失败: ${result.message}")
                }
            }
        }
    }

    fun dismissUpdateDialog() {
        _showUpdateDialog.value = false
    }

    fun getCurrentVersion(): String {
        var version = "1.0.0"
        viewModelScope.launch {
            version = updateService.getCurrentVersion()
        }
        return version
    }

    fun hasReachedCrashThreshold(): Boolean {
        return errorReportManager.hasReachedCrashThreshold()
    }

    fun getErrorReportCount(): Int {
        return errorReportManager.getErrorReports().size
    }

    fun collectLogsForShare(): File? {
        return errorReportManager.collectAllLogs()
    }

    fun clearErrorReports() {
        errorReportManager.clearErrorReports()
    }
}

sealed class UpdateUiState {
    data object Idle : UpdateUiState()
    data object Checking : UpdateUiState()
    data class UpdateAvailable(val updateInfo: UpdateInfo) : UpdateUiState()
    data object Downloading : UpdateUiState()
    data class DownloadComplete(val apkFile: File) : UpdateUiState()
    data object Installing : UpdateUiState()
    data class Error(val message: String) : UpdateUiState()
}
