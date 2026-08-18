package com.lingshu.feature.control.data.scenes

import com.lingshu.core.common.error.Result
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.feature.control.domain.CommandExecutor
import com.lingshu.feature.control.domain.scenes.GenericScene
import com.lingshu.feature.control.domain.scenes.SceneExecutionResult
import com.lingshu.feature.control.domain.scenes.SceneExecutor
import com.lingshu.feature.control.domain.scenes.SceneMatch
import com.lingshu.feature.control.domain.scenes.SceneProgress
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SceneExecutorImpl @Inject constructor(
    private val commandExecutor: CommandExecutor
) : SceneExecutor {

    companion object { private const val TAG = "SceneExec" }

    private val listeners = mutableListOf<SceneExecutor.ProgressListener>()
    @Volatile private var lastProgress: SceneProgress? = null

    override fun lastProgress(): SceneProgress? = lastProgress

    override suspend fun execute(match: SceneMatch.Ok): SceneExecutionResult {
        val scene = match.scene
        val total = match.commands.size
        emit(SceneProgress(scene.sceneId, -1, total, "开始执行 ${scene.displayName}", false, null))
        var failedIdx = -1
        var failedMsg: String? = null
        match.commands.forEachIndexed { i, cmd ->
            emit(SceneProgress(scene.sceneId, i, total, match.progressTexts.getOrNull(i) ?: "步骤 $i"))
            when (val r = commandExecutor.execute(cmd)) {
                is Result.Success -> {
                    LingShuLog.i(TAG, "步骤 $i 成功：${cmd::class.java.simpleName}")
                }
                is Result.Error -> {
                    LingShuLog.w(TAG, "步骤 $i 失败：${cmd::class.java.simpleName} ${r.message}")
                    failedIdx = i
                    failedMsg = r.message
                    emit(SceneProgress(scene.sceneId, i, total, match.progressTexts.getOrNull(i) ?: "步骤 $i", done = false, error = r.message))
                    return@forEachIndexed
                }
            }
        }
        emit(SceneProgress(scene.sceneId, total - 1, total, match.progressTexts.lastOrNull() ?: "完成", done = true))
        return if (failedIdx < 0) {
            SceneExecutionResult.Success(renderCompletionText(scene, match))
        } else {
            SceneExecutionResult.PartialFailure(failedIdx, failedMsg ?: "步骤 $failedIdx 失败")
        }
    }

    private fun emit(p: SceneProgress) {
        lastProgress = p
        listeners.forEach { runCatching { it.onProgress(p) } }
    }

    private fun renderCompletionText(scene: GenericScene, match: SceneMatch.Ok): String {
        var out = scene.completionText
        val regex = Regex("""\{([^{}]+)\}""")
        regex.findAll(scene.completionText).forEach { m ->
            val raw = m.groupValues[1]
            // 兼容 「或」「｜」「|」「/」 四种写法，方便声明式写模板
            val parts = raw.split("或", "｜", "|", "/", limit = 2).map { it.trim() }
            val slotName = parts[0]
            val default = parts.getOrNull(1) ?: ""
            val v = match.filledSlots[slotName]?.takeIf { it.isNotBlank() } ?: default
            out = out.replace(m.value, v)
        }
        return out
    }
}
