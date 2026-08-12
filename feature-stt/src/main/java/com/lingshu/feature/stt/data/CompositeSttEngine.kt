package com.lingshu.feature.stt.data

import com.lingshu.core.common.event.ISttEngine
import com.lingshu.core.common.event.SttResult
import com.lingshu.core.common.log.LingShuLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 组合 STT 引擎：优先使用 Vosk 离线识别，Vosk 不可用时 fallback 到系统 SpeechRecognizer。
 */
@Singleton
class CompositeSttEngine @Inject constructor(
    private val voskSttEngine: VoskSttEngine,
    private val systemSttEngine: SttEngineImpl
) : ISttEngine {

    private val moduleTag = "CompositeStt"
    private var activeEngine: ISttEngine? = null

    override fun startListening(onResult: (SttResult) -> Unit, onError: (String) -> Unit) {
        // 优先 Vosk
        if (voskSttEngine.isAvailable()) {
            LingShuLog.i(moduleTag, "使用 Vosk 离线识别")
            activeEngine = voskSttEngine
            voskSttEngine.startListening(onResult, onError)
            return
        }

        // Fallback 系统识别
        if (systemSttEngine.isAvailable()) {
            LingShuLog.i(moduleTag, "Vosk 不可用，使用系统 SpeechRecognizer")
            activeEngine = systemSttEngine
            systemSttEngine.startListening(onResult, onError)
            return
        }

        LingShuLog.w(moduleTag, "所有 STT 引擎均不可用")
        onError("语音识别不可用：请部署 Vosk 模型或确保系统语音服务可用")
    }

    override fun stopListening() {
        activeEngine?.stopListening()
        activeEngine = null
    }

    override fun isAvailable(): Boolean {
        return voskSttEngine.isAvailable() || systemSttEngine.isAvailable()
    }

    override fun cancel() {
        activeEngine?.cancel()
        activeEngine = null
    }
}
