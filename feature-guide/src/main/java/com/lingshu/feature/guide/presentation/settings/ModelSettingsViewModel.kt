package com.lingshu.feature.guide.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.core.common.state.UiState
import com.lingshu.core.data.llm.ChatMessage
import com.lingshu.core.data.llm.LlmConfig
import com.lingshu.core.data.llm.LlmConfigStore
import com.lingshu.core.data.llm.LlmRouter
import com.lingshu.core.data.llm.ModelProviderType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "ModelSettings"

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ModelSettingsViewModel @Inject constructor(
    private val llmConfigStore: LlmConfigStore,
    private val llmRouter: LlmRouter
) : ViewModel() {

    private val _currentProvider = MutableStateFlow(ModelProviderType.DEEPSEEK)
    val currentProvider: StateFlow<ModelProviderType> = _currentProvider.asStateFlow()

    private val _configForCurrent = MutableStateFlow(LlmConfig())
    val configForCurrent: StateFlow<LlmConfig> = _configForCurrent.asStateFlow()

    private val _memoryInjected = MutableStateFlow(true)
    val memoryInjected: StateFlow<Boolean> = _memoryInjected.asStateFlow()

    private val _personaEvolve = MutableStateFlow(true)
    val personaEvolve: StateFlow<Boolean> = _personaEvolve.asStateFlow()

    private val _ragEnabled = MutableStateFlow(false)
    val ragEnabled: StateFlow<Boolean> = _ragEnabled.asStateFlow()

    private val _ragThreshold = MutableStateFlow(0.65f)
    val ragThreshold: StateFlow<Float> = _ragThreshold.asStateFlow()

    private val _historyCount = MutableStateFlow(20)
    val historyCount: StateFlow<Int> = _historyCount.asStateFlow()

    private val _testResult = MutableStateFlow<UiState<String>>(UiState.Idle)
    val testResult: StateFlow<UiState<String>> = _testResult.asStateFlow()

    init {
        LingShuLog.i(TAG, "init: ViewModel created")

        llmConfigStore.selectedProvider
            .onEach { provider ->
                LingShuLog.d(TAG, "selectedProvider collected: ${provider.name}")
                _currentProvider.value = provider
            }
            .launchIn(viewModelScope)

        _currentProvider
            .flatMapLatest { provider ->
                LingShuLog.d(TAG, "currentProvider changed, loading config for ${provider.name}")
                llmConfigStore.getConfigFlow(provider)
            }
            .onEach { config ->
                _configForCurrent.value = config
                LingShuLog.d(
                    TAG,
                    "configForCurrent updated: provider=${config.provider.name}, " +
                            "model=${config.modelName}, url=${config.baseUrl}"
                )
            }
            .launchIn(viewModelScope)
    }

    fun selectProvider(provider: ModelProviderType) {
        LingShuLog.i(TAG, "selectProvider: switching to ${provider.name}")
        viewModelScope.launch {
            _currentProvider.value = provider
            llmConfigStore.setSelectedProvider(provider)
        }
    }

    fun updateBaseUrl(value: String) {
        LingShuLog.d(TAG, "updateBaseUrl: $value")
        val current = _configForCurrent.value
        _configForCurrent.value = current.copy(baseUrl = value)
        persistConfig()
    }

    fun updateApiKey(value: String) {
        LingShuLog.d(TAG, "updateApiKey: length=${value.length}")
        val current = _configForCurrent.value
        _configForCurrent.value = current.copy(apiKey = value)
        persistConfig()
    }

    fun updateModelName(value: String) {
        LingShuLog.d(TAG, "updateModelName: $value")
        val current = _configForCurrent.value
        _configForCurrent.value = current.copy(modelName = value)
        persistConfig()
    }

    fun updateTemperature(value: Float) {
        LingShuLog.d(TAG, "updateTemperature: $value")
        val current = _configForCurrent.value
        _configForCurrent.value = current.copy(temperature = value)
        persistConfig()
    }

    fun updateTopP(value: Float) {
        LingShuLog.d(TAG, "updateTopP: $value")
        val current = _configForCurrent.value
        _configForCurrent.value = current.copy(topP = value)
        persistConfig()
    }

    fun updateMaxTokens(value: Int) {
        LingShuLog.d(TAG, "updateMaxTokens: $value")
        val current = _configForCurrent.value
        _configForCurrent.value = current.copy(maxTokens = value)
        persistConfig()
    }

    fun updateTimeoutSeconds(value: Int) {
        LingShuLog.d(TAG, "updateTimeoutSeconds: $value")
        val current = _configForCurrent.value
        _configForCurrent.value = current.copy(timeoutSeconds = value)
        persistConfig()
    }

    private fun persistConfig() {
        viewModelScope.launch {
            val config = _configForCurrent.value
            LingShuLog.d(TAG, "persistConfig: saving config for ${config.provider.name}")
            llmConfigStore.saveConfig(config)
        }
    }

    fun toggleMemoryInjected(enabled: Boolean) {
        LingShuLog.i(TAG, "toggleMemoryInjected: $enabled")
        _memoryInjected.value = enabled
    }

    fun togglePersonaEvolve(enabled: Boolean) {
        LingShuLog.i(TAG, "togglePersonaEvolve: $enabled")
        _personaEvolve.value = enabled
    }

    fun toggleRagEnabled(enabled: Boolean) {
        LingShuLog.i(TAG, "toggleRagEnabled: $enabled")
        _ragEnabled.value = enabled
    }

    fun updateRagThreshold(value: Float) {
        LingShuLog.d(TAG, "updateRagThreshold: $value")
        _ragThreshold.value = value
    }

    fun updateHistoryCount(value: Int) {
        LingShuLog.d(TAG, "updateHistoryCount: $value")
        _historyCount.value = value
    }

    fun testConnection() {
        LingShuLog.i(TAG, "testConnection: start testing provider=${_currentProvider.value.name}")
        val config = _configForCurrent.value
        _testResult.value = UiState.Loading

        viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            val messages = listOf(ChatMessage(role = "user", content = "ping"))

            try {
                when (val result = llmRouter.chat(
                    messages = messages,
                    primaryConfig = config,
                    traceId = "model_settings_test_${System.currentTimeMillis()}"
                )) {
                    is com.lingshu.core.common.error.Result.Success -> {
                        val elapsed = System.currentTimeMillis() - startTime
                        val elapsedSec = elapsed / 1000.0
                        LingShuLog.i(
                            TAG,
                            "testConnection: success, elapsedMs=$elapsed, elapsedSec=$elapsedSec"
                        )
                        when {
                            elapsed < 2000 -> {
                                _testResult.value = UiState.Success("✓ 连接成功 (${String.format("%.1f", elapsedSec)}s)")
                            }
                            elapsed < 5000 -> {
                                _testResult.value = UiState.Success("△ 连接较慢 (${String.format("%.1f", elapsedSec)}s)")
                            }
                            else -> {
                                _testResult.value = UiState.Success("△ 连接超时风险 (${String.format("%.1f", elapsedSec)}s)")
                            }
                        }
                    }
                    is com.lingshu.core.common.error.Result.Error -> {
                        val elapsed = System.currentTimeMillis() - startTime
                        LingShuLog.e(
                            TAG,
                            "testConnection: error, code=${result.code}, " +
                                    "msg=${result.message}, elapsedMs=$elapsed"
                        )
                        _testResult.value = UiState.Error(
                            code = result.code,
                            message = "✗ 连接失败: ${result.message.take(60)}"
                        )
                    }
                }
            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - startTime
                LingShuLog.e(
                    TAG,
                    "testConnection: exception, elapsedMs=$elapsed",
                    e
                )
                _testResult.value = UiState.Error(
                    code = "EXCEPTION",
                    message = "✗ 连接异常: ${e.message?.take(60) ?: "Unknown error"}"
                )
            }
        }
    }
}
