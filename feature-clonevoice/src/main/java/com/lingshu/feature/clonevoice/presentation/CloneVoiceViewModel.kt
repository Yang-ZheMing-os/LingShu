package com.lingshu.feature.clonevoice.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lingshu.core.common.error.Result
import com.lingshu.core.common.state.UiState
import com.lingshu.feature.clonevoice.domain.ICloneVoiceService
import com.lingshu.feature.clonevoice.domain.Voice
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import kotlin.math.sin

@HiltViewModel
class CloneVoiceViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cloneVoiceService: ICloneVoiceService
) : ViewModel() {

    private val _voices = MutableStateFlow<List<Voice>>(emptyList())
    val voices: StateFlow<List<Voice>> = _voices.asStateFlow()

    private val _currentVoice = MutableStateFlow<Voice?>(null)
    val currentVoice: StateFlow<Voice?> = _currentVoice.asStateFlow()

    private val _cloneState = MutableStateFlow<UiState<String>>(UiState.Idle)
    val cloneState: StateFlow<UiState<String>> = _cloneState.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingTime = MutableStateFlow(0L)
    val recordingTime: StateFlow<Long> = _recordingTime.asStateFlow()

    private val _waveformAmplitudes = MutableStateFlow<List<Float>>(emptyList())
    val waveformAmplitudes: StateFlow<List<Float>> = _waveformAmplitudes.asStateFlow()

    private val _previewState = MutableStateFlow<UiState<Unit>>(UiState.Idle)
    val previewState: StateFlow<UiState<Unit>> = _previewState.asStateFlow()

    private var recordingStartTime = 0L
    private var currentRecordingFile: File? = null

    init {
        loadVoices()
    }

    private fun loadVoices() {
        _voices.value = cloneVoiceService.listVoices()
        _currentVoice.value = cloneVoiceService.getCurrentVoice()
    }

    /**
     * 开始录音：在 cacheDir/recordings 下创建录音输出文件，并调用服务启动 MediaRecorder。
     * 立即进入录音态以避免重复点击，失败时回退并上报错误。
     */
    fun startRecording() {
        viewModelScope.launch {
            _cloneState.value = UiState.Idle
            recordingStartTime = System.currentTimeMillis()
            _recordingTime.value = 0L
            _waveformAmplitudes.value = emptyList()
            _isRecording.value = true

            val recordingsDir = File(context.cacheDir, "recordings").apply { mkdirs() }
            val file = File(recordingsDir, "recording_${System.currentTimeMillis()}.amr")
            currentRecordingFile = file

            val result = cloneVoiceService.startRecording(file)
            when (result) {
                is Result.Success -> {
                    // 录音已真实启动，保持 isRecording=true
                    recordingStartTime = System.currentTimeMillis()
                }
                is Result.Error -> {
                    _isRecording.value = false
                    currentRecordingFile = null
                    _cloneState.value = UiState.Error(result.code, result.message)
                }
            }
        }
    }

    /**
     * 停止录音：调用服务停止 MediaRecorder 并返回录音文件。
     * 由于 stopRecording 需要返回 File 给 UI，采用回调方式通知；
     * 录音完成后自动触发 cloneVoice（在 Screen 的回调中完成）。
     */
    fun stopRecording(onComplete: (File?) -> Unit) {
        viewModelScope.launch {
            val result = cloneVoiceService.stopRecording()
            _isRecording.value = false
            val file = when (result) {
                is Result.Success -> result.data
                is Result.Error -> {
                    _cloneState.value = UiState.Error(result.code, result.message)
                    null
                }
            }
            currentRecordingFile = null
            onComplete(file)
        }
    }

    /**
     * 更新录音时长与波形。使用基于时间的稳定正弦波形，替代 Math.random() 假数据。
     */
    fun updateRecordingTime() {
        if (_isRecording.value) {
            val elapsed = System.currentTimeMillis() - recordingStartTime
            _recordingTime.value = elapsed
            // 基于时间的稳定正弦波形，避免随机抖动
            val t = elapsed / 1000f
            val amplitude = (0.5f + 0.4f * sin(t * 3.5).toFloat()).coerceIn(0.15f, 1f)
            val currentList = _waveformAmplitudes.value.toMutableList()
            currentList.add(amplitude)
            if (currentList.size > 100) {
                currentList.removeAt(0)
            }
            _waveformAmplitudes.value = currentList
        }
    }

    fun cloneVoice(audioFile: File) {
        viewModelScope.launch {
            _cloneState.value = UiState.Loading
            val result = cloneVoiceService.cloneAudio(audioFile)
            when (result) {
                is Result.Success -> {
                    _cloneState.value = UiState.Success(result.data)
                    // 刷新列表，并自动选中新创建的声音
                    loadVoices()
                    val newId = result.data
                    applyVoice(newId)
                }
                is Result.Error -> {
                    _cloneState.value = UiState.Error(result.code, result.message)
                }
            }
        }
    }

    fun setCurrentVoice(voiceId: String) {
        viewModelScope.launch {
            // 选中即应用：将音色配置写入 TTS 引擎
            applyVoice(voiceId)
        }
    }

    /**
     * 应用指定音色到 TTS 引擎并切换当前音色。
     * 成功后刷新当前音色状态。
     */
    fun applyVoice(voiceId: String) {
        viewModelScope.launch {
            val result = cloneVoiceService.applyVoice(voiceId)
            if (result.isSuccess) {
                _currentVoice.value = cloneVoiceService.getCurrentVoice()
            }
        }
    }

    fun deleteVoice(voiceId: String) {
        viewModelScope.launch {
            val result = cloneVoiceService.deleteVoice(voiceId)
            if (result.isSuccess) {
                loadVoices()
            }
        }
    }

    fun previewVoice(voiceId: String, text: String) {
        viewModelScope.launch {
            _previewState.value = UiState.Loading
            // 试听前先应用该音色配置，确保使用当前选中音色合成
            cloneVoiceService.applyVoice(voiceId)
            val result = cloneVoiceService.previewVoice(voiceId, text)
            when (result) {
                is Result.Success -> {
                    _previewState.value = UiState.Success(Unit)
                }
                is Result.Error -> {
                    _previewState.value = UiState.Error(result.code, result.message)
                }
            }
        }
    }

    fun resetCloneState() {
        _cloneState.value = UiState.Idle
    }

    fun resetPreviewState() {
        _previewState.value = UiState.Idle
    }

    // ==================== Day2-2：音色库分享 ====================
    private val _presetOperation = MutableStateFlow<UiState<String>>(UiState.Idle)
    val presetOperation: StateFlow<UiState<String>> = _presetOperation.asStateFlow()

    fun createCustomPreset(
        name: String,
        author: String,
        description: String,
        tags: List<String>,
        voiceName: String?,
        pitch: Float,
        rate: Float
    ) {
        viewModelScope.launch {
            _presetOperation.value = UiState.Loading
            val result = cloneVoiceService.createCustomPreset(name, author, description, tags, voiceName, pitch, rate)
            when (result) {
                is Result.Success -> {
                    loadVoices()
                    applyVoice(result.data)
                    _presetOperation.value = UiState.Success("创建成功: $name")
                }
                is Result.Error -> {
                    _presetOperation.value = UiState.Error(result.code, result.message)
                }
            }
        }
    }

    fun importPreset(file: File) {
        viewModelScope.launch {
            _presetOperation.value = UiState.Loading
            val result = cloneVoiceService.importPreset(file)
            when (result) {
                is Result.Success -> {
                    loadVoices()
                    applyVoice(result.data)
                    _presetOperation.value = UiState.Success("导入成功")
                }
                is Result.Error -> {
                    _presetOperation.value = UiState.Error(result.code, result.message)
                }
            }
        }
    }

    fun exportPreset(voiceId: String, targetFile: File, onDone: (File?) -> Unit) {
        viewModelScope.launch {
            _presetOperation.value = UiState.Loading
            val result = cloneVoiceService.exportPreset(voiceId, targetFile)
            when (result) {
                is Result.Success -> {
                    _presetOperation.value = UiState.Success("导出成功: ${result.data.name}")
                    onDone(result.data)
                }
                is Result.Error -> {
                    _presetOperation.value = UiState.Error(result.code, result.message)
                    onDone(null)
                }
            }
        }
    }

    fun resetPresetOperation() {
        _presetOperation.value = UiState.Idle
    }
}
