package com.lingshu.agent.feature.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lingshu.agent.core.database.dao.ClonedVoiceDao
import com.lingshu.agent.core.database.entity.ClonedVoiceEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

enum class RecordingState {
    IDLE,
    RECORDING,
    CLONING,
    DONE,
    ERROR
}

data class VoiceCloneUiState(
    val recordingState: RecordingState = RecordingState.IDLE,
    val elapsedSeconds: Int = 0,
    val clonedVoices: List<ClonedVoiceEntity> = emptyList(),
    val errorMessage: String? = null,
    val lastClonedVoice: ClonedVoiceEntity? = null
)

@HiltViewModel
class VoiceCloneViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clonedVoiceDao: ClonedVoiceDao,
    private val voiceCloneProvider: VoiceCloneProvider
) : ViewModel() {

    companion object {
        const val MIN_DURATION_SECONDS = 10
        const val MAX_DURATION_SECONDS = 30
        private const val TIMER_INTERVAL_MS = 50L
    }

    private val _uiState = MutableStateFlow(VoiceCloneUiState())
    val uiState: StateFlow<VoiceCloneUiState> = _uiState.asStateFlow()

    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var timerJob: Job? = null

    init {
        observeClonedVoices()
    }

    private fun observeClonedVoices() {
        viewModelScope.launch {
            clonedVoiceDao.observeAll().collect { voices ->
                _uiState.value = _uiState.value.copy(clonedVoices = voices)
            }
        }
    }

    fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun startRecording() {
        if (!hasAudioPermission()) {
            _uiState.value = _uiState.value.copy(
                recordingState = RecordingState.ERROR,
                errorMessage = "缺少录音权限"
            )
            return
        }

        val file = File(context.cacheDir, "voice_sample_${System.currentTimeMillis()}.m4a")
        outputFile = file

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        try {
            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(96000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            _uiState.value = _uiState.value.copy(
                recordingState = RecordingState.RECORDING,
                elapsedSeconds = 0,
                errorMessage = null
            )

            startTimer()
        } catch (e: IOException) {
            _uiState.value = _uiState.value.copy(
                recordingState = RecordingState.ERROR,
                errorMessage = "录音启动失败: ${e.message}"
            )
            releaseRecorder()
        }
    }

    fun stopRecording() {
        timerJob?.cancel()
        val elapsed = _uiState.value.elapsedSeconds

        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (_: Exception) {
        }
        mediaRecorder = null

        if (elapsed < MIN_DURATION_SECONDS) {
            cleanupOutputFile()
            _uiState.value = _uiState.value.copy(
                recordingState = RecordingState.IDLE,
                errorMessage = "录音时长不足${MIN_DURATION_SECONDS}秒，请重新录制"
            )
            return
        }

        startCloning()
    }

    fun cancelRecording() {
        timerJob?.cancel()
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (_: Exception) {
        }
        mediaRecorder = null
        cleanupOutputFile()

        _uiState.value = _uiState.value.copy(
            recordingState = RecordingState.IDLE,
            elapsedSeconds = 0,
            errorMessage = null
        )
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    private fun startCloning() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(recordingState = RecordingState.CLONING)

            val file = outputFile ?: run {
                _uiState.value = _uiState.value.copy(
                    recordingState = RecordingState.ERROR,
                    errorMessage = "录音文件丢失"
                )
                return@launch
            }

            val result = withContext(Dispatchers.IO) {
                voiceCloneProvider.cloneVoice(
                    name = "语音克隆",
                    sampleFilePath = file.absolutePath
                )
            }

            if (result.success) {
                val entity = ClonedVoiceEntity(
                    name = "语音克隆 ${formatTimestamp(System.currentTimeMillis())}",
                    modelFilePath = result.modelFilePath,
                    sampleFilePath = file.absolutePath,
                    durationSeconds = _uiState.value.elapsedSeconds,
                    isActive = true,
                    createdAt = System.currentTimeMillis()
                )

                withContext(Dispatchers.IO) {
                    clonedVoiceDao.deactivateAll()
                    clonedVoiceDao.insert(entity)
                }

                _uiState.value = _uiState.value.copy(
                    recordingState = RecordingState.DONE,
                    elapsedSeconds = 0,
                    errorMessage = null,
                    lastClonedVoice = entity
                )
            } else {
                cleanupOutputFile()
                _uiState.value = _uiState.value.copy(
                    recordingState = RecordingState.ERROR,
                    errorMessage = result.errorMessage ?: "克隆失败"
                )
            }
        }
    }

    private fun startTimer() {
        timerJob = viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            while (true) {
                delay(TIMER_INTERVAL_MS)
                val elapsed = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                _uiState.value = _uiState.value.copy(elapsedSeconds = elapsed)

                if (elapsed >= MAX_DURATION_SECONDS) {
                    stopRecording()
                    break
                }
            }
        }
    }

    fun deleteVoice(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                clonedVoiceDao.deleteById(id)
            }
        }
    }

    fun setActiveVoice(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                clonedVoiceDao.deactivateAll()
                clonedVoiceDao.activate(id)
            }
        }
    }

    fun resetState() {
        _uiState.value = _uiState.value.copy(
            recordingState = RecordingState.IDLE,
            elapsedSeconds = 0,
            errorMessage = null,
            lastClonedVoice = null
        )
    }

    private fun releaseRecorder() {
        try {
            mediaRecorder?.release()
        } catch (_: Exception) {
        }
        mediaRecorder = null
    }

    private fun cleanupOutputFile() {
        outputFile?.let {
            try {
                it.delete()
            } catch (_: Exception) {
            }
        }
        outputFile = null
    }

    private fun formatTimestamp(timestamp: Long): String {
        val sdf = java.text.SimpleDateFormat("MMdd_HHmmss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
        releaseRecorder()
        cleanupOutputFile()
    }
}
