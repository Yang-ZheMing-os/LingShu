package com.lingshu.feature.clonevoice.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lingshu.core.common.error.Result
import com.lingshu.core.common.state.UiState
import com.lingshu.feature.clonevoice.domain.ICloneVoiceService
import com.lingshu.feature.clonevoice.domain.Voice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class CloneVoiceViewModel @Inject constructor(
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

    init {
        loadVoices()
    }

    private fun loadVoices() {
        _voices.value = cloneVoiceService.listVoices()
        _currentVoice.value = cloneVoiceService.getCurrentVoice()
    }

    fun startRecording() {
        _isRecording.value = true
        _recordingTime.value = 0L
        _waveformAmplitudes.value = emptyList()
        recordingStartTime = System.currentTimeMillis()
    }

    fun stopRecording(): File? {
        _isRecording.value = false
        return null
    }

    fun updateRecordingTime() {
        if (_isRecording.value) {
            _recordingTime.value = System.currentTimeMillis() - recordingStartTime
            val newAmplitude = (Math.random().toFloat() * 0.8f + 0.2f)
            val currentList = _waveformAmplitudes.value.toMutableList()
            currentList.add(newAmplitude)
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
                    loadVoices()
                }
                is Result.Error -> {
                    _cloneState.value = UiState.Error(result.exception, result.code)
                }
            }
        }
    }

    fun setCurrentVoice(voiceId: String) {
        viewModelScope.launch {
            val result = cloneVoiceService.setCurrentVoice(voiceId)
            if (result.isSuccess()) {
                _currentVoice.value = cloneVoiceService.getCurrentVoice()
            }
        }
    }

    fun deleteVoice(voiceId: String) {
        viewModelScope.launch {
            val result = cloneVoiceService.deleteVoice(voiceId)
            if (result.isSuccess()) {
                loadVoices()
            }
        }
    }

    fun previewVoice(voiceId: String, text: String) {
        viewModelScope.launch {
            _previewState.value = UiState.Loading
            val result = cloneVoiceService.previewVoice(voiceId, text)
            when (result) {
                is Result.Success -> {
                    _previewState.value = UiState.Success(Unit)
                }
                is Result.Error -> {
                    _previewState.value = UiState.Error(result.exception, result.code)
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
}
