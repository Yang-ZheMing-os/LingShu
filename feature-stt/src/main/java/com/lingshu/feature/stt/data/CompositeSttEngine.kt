package com.lingshu.feature.stt.data

import com.lingshu.core.common.event.ISttEngine
import com.lingshu.core.common.event.SttResult
import com.lingshu.core.common.log.LingShuLog
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CompositeSttEngine @Inject constructor(
    private val sherpaOnnxSttEngine: SherpaOnnxSttEngine,
    private val voskSttEngine: VoskSttEngine,
    private val systemSttEngine: SttEngineImpl
) : ISttEngine {

    private val moduleTag = "CompositeStt"
    private var activeEngine: ISttEngine? = null

    override fun startListening(onResult: (SttResult) -> Unit, onError: (String) -> Unit) {
        if (sherpaOnnxSttEngine.isAvailable()) {
            LingShuLog.i(moduleTag, "使用 Sherpa-ONNX SenseVoice 离线识别")
            activeEngine = sherpaOnnxSttEngine
            sherpaOnnxSttEngine.startListening(onResult, onError)
            return
        }

        if (voskSttEngine.isAvailable()) {
            LingShuLog.i(moduleTag, "Sherpa 不可用，使用 Vosk 离线识别")
            activeEngine = voskSttEngine
            voskSttEngine.startListening(onResult, onError)
            return
        }

        if (systemSttEngine.isAvailable()) {
            LingShuLog.i(moduleTag, "离线模型不可用，使用系统 SpeechRecognizer")
            activeEngine = systemSttEngine
            systemSttEngine.startListening(onResult, onError)
            return
        }

        LingShuLog.w(moduleTag, "所有 STT 引擎均不可用")
        onError("语音识别不可用：请部署 Sherpa-ONNX 或 Vosk 模型，或确保系统语音服务可用")
    }

    override fun stopListening() {
        activeEngine?.stopListening()
        activeEngine = null
    }

    override fun isAvailable(): Boolean {
        return sherpaOnnxSttEngine.isAvailable() ||
                voskSttEngine.isAvailable() ||
                systemSttEngine.isAvailable()
    }

    override fun cancel() {
        activeEngine?.cancel()
        activeEngine = null
    }
}
