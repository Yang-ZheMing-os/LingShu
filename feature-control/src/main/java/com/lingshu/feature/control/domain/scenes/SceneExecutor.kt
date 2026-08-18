package com.lingshu.feature.control.domain.scenes

import com.lingshu.core.common.error.Result

/**
 * 按 SceneMatch.Ok.commands 一条条执行；执行前、中、后都会 emit 进度事件（给 ChatViewModel / UI 画"正在第 1 步… 第 2 步…"进度条）。
 * 若 SceneMatch 是 MissingSlot，会 ask user 填充；填充后回到 execute()。
 */
interface SceneExecutor {
    suspend fun execute(match: SceneMatch.Ok): SceneExecutionResult
    fun lastProgress(): SceneProgress?
    interface ProgressListener {
        fun onProgress(progress: SceneProgress)
    }
}

data class SceneProgress(
    val sceneId: String,
    val currentStepIndex: Int,    // 0-based
    val totalSteps: Int,
    val stepLabel: String,
    val done: Boolean = false,
    val error: String? = null
)

sealed class SceneExecutionResult {
    data class Success(val finalText: String) : SceneExecutionResult()
    data class PartialFailure(val failedStepIndex: Int, val message: String) : SceneExecutionResult()
}
