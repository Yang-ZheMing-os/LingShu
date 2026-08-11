package com.lingshu.feature.wakeword.data

import android.content.Context
import com.lingshu.core.common.error.Result
import com.lingshu.core.common.error.ErrorCodes
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.feature.wakeword.domain.IWakeWordEngine
import com.lingshu.feature.wakeword.domain.WakeWordEvent

class PorcupineWakeWordEngine(
    private val context: Context,
    private val fallbackEngine: FallbackWakeWordEngine,
    private val accessKey: String? = null,
    private val keyword: String = "Hey Google"
) : IWakeWordEngine {

    private val listeners = mutableListOf<(WakeWordEvent) -> Unit>()
    private var running = false
    private var useFallback = false

    override suspend fun start(): Result<Unit> {
        if (running) {
            return Result.Success(Unit)
        }

        if (accessKey.isNullOrBlank()) {
            LingShuLog.w("PorcupineWakeWord", "未配置 Porcupine AccessKey，使用降级方案")
            useFallback = true
            val result = fallbackEngine.start()
            if (result.isSuccess()) {
                running = true
            }
            return result
        }

        return try {
            startPorcupine(accessKey)
            running = true
            useFallback = false
            LingShuLog.d("PorcupineWakeWord", "Porcupine 唤醒词引擎启动成功，关键词: $keyword")
            Result.Success(Unit)
        } catch (e: Exception) {
            LingShuLog.e("PorcupineWakeWord", "Porcupine 启动失败，切换到降级方案", e)
            useFallback = true
            val fallbackResult = fallbackEngine.start()
            if (fallbackResult.isSuccess()) {
                running = true
            }
            return fallbackResult
        }
    }

    private fun startPorcupine(accessKey: String) {
        // TODO: 接入真实的 Porcupine SDK
        // Porcupine 初始化代码示例：
        // val porcupine = Porcupine.Builder()
        //     .setAccessKey(accessKey)
        //     .setKeyword(Porcupine.BuiltInKeyword.HEY_GOOGLE)
        //     .setSensitivity(0.5f)
        //     .build(context)
        //
        // porcupineManager = PorcupineManager.Builder()
        //     .setAccessKey(accessKey)
        //     .setKeyword(Porcupine.BuiltInKeyword.HEY_GOOGLE)
        //     .setWakeWordCallback { keywordIndex ->
        //         val event = WakeWordEvent(
        //             keyword = keyword,
        //             timestamp = System.currentTimeMillis()
        //         )
        //         notifyListeners(event)
        //     }
        //     .build(context)
        //
        // porcupineManager.start()

        throw UnsupportedOperationException("Porcupine SDK 未集成，使用降级方案")
    }

    private fun stopPorcupine() {
        // TODO: 停止 Porcupine
        // porcupineManager?.stop()
        // porcupineManager?.delete()
        // porcupineManager = null
    }

    override suspend fun stop() {
        running = false
        if (useFallback) {
            fallbackEngine.stop()
        } else {
            try {
                stopPorcupine()
            } catch (e: Exception) {
                LingShuLog.w("PorcupineWakeWord", "停止 Porcupine 时出错", e)
            }
        }
        LingShuLog.d("PorcupineWakeWord", "唤醒词引擎已停止")
    }

    override fun registerListener(listener: (WakeWordEvent) -> Unit) {
        listeners.add(listener)
        fallbackEngine.registerListener(listener)
    }

    override fun unregisterListener(listener: (WakeWordEvent) -> Unit) {
        listeners.remove(listener)
        fallbackEngine.unregisterListener(listener)
    }

    override fun isRunning(): Boolean = running

    fun isFallbackMode(): Boolean = useFallback

    private fun notifyListeners(event: WakeWordEvent) {
        listeners.forEach { it(event) }
    }
}
