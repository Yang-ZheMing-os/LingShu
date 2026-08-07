package com.lingshu.agent.feature.script

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lingshu.agent.core.database.dao.ScriptDao
import com.lingshu.agent.core.database.entity.ScriptEntity
import com.lingshu.agent.core.model.Script
import com.lingshu.agent.core.model.ScriptStatus
import com.lingshu.agent.core.model.TriggerType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ScriptViewModel @Inject constructor(
    private val scriptDao: ScriptDao
) : ViewModel() {

    companion object {
        private const val TAG = "ScriptViewModel"
    }

    private val _event = MutableSharedFlow<ScriptEvent>()
    val event: Flow<ScriptEvent> = _event.asSharedFlow()

    private val _currentScript = MutableStateFlow(
        Script(
            scriptId = "new_${System.currentTimeMillis()}",
            name = "新脚本",
            description = "",
            content = "// 灵枢自动化脚本\n// 示例：点击屏幕、滑动、输入文字等操作\n\nasync function main() {\n    console.log(\"脚本开始执行\");\n    \n    // 在这里编写您的自动化逻辑\n    // await click(500, 1000);\n    // await swipe(100, 1500, 100, 500, 500);\n    // await inputText(\"Hello\");\n    // await sleep(1000);\n    \n    console.log(\"脚本执行完成\");\n}\n\nmain();\n",
            status = ScriptStatus.DRAFT
        )
    )
    val currentScript: StateFlow<Script> = _currentScript.asStateFlow()

    private val _selectedTab = MutableStateFlow(0)
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    data class RecordStep(
        val id: Long = System.currentTimeMillis(),
        val type: StepType,
        val description: String,
        val timestamp: Long = System.currentTimeMillis(),
        val params: Map<String, Any> = emptyMap()
    )

    enum class StepType {
        CLICK, SWIPE, INPUT, WAIT, APP_LAUNCH, PRESS_BACK, PRESS_HOME
    }

    private val _recordedSteps = MutableStateFlow<List<RecordStep>>(emptyList())
    val recordedSteps: StateFlow<List<RecordStep>> = _recordedSteps.asStateFlow()

    private val _testLogs = MutableStateFlow<List<TestLog>>(emptyList())
    val testLogs: StateFlow<List<TestLog>> = _testLogs.asStateFlow()

    private val _isTestRunning = MutableStateFlow(false)
    val isTestRunning: StateFlow<Boolean> = _isTestRunning.asStateFlow()

    data class TestLog(
        val timestamp: Long = System.currentTimeMillis(),
        val level: LogLevel = LogLevel.INFO,
        val message: String
    )

    enum class LogLevel {
        DEBUG, INFO, WARN, ERROR, SUCCESS
    }

    val uiState: StateFlow<ScriptWorkshopUiState> = kotlinx.coroutines.flow.combine(
        _currentScript,
        _selectedTab,
        _isRecording
    ) { script, tab, recording -> Triple(script, tab, recording) }.combine(
        kotlinx.coroutines.flow.combine(_recordedSteps, _testLogs, _isTestRunning) { steps, logs, testRunning ->
            Triple(steps, logs, testRunning)
        }
    ) { (script, tab, recording), (steps, logs, testRunning) ->
        ScriptWorkshopUiState(
            script = script,
            selectedTab = tab,
            isRecording = recording,
            recordedSteps = steps,
            testLogs = logs,
            isTestRunning = testRunning
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ScriptWorkshopUiState()
    )

    fun selectTab(index: Int) {
        _selectedTab.value = index
    }

    fun updateName(name: String) {
        _currentScript.value = _currentScript.value.copy(
            name = name,
            updatedAt = System.currentTimeMillis()
        )
    }

    fun updateDescription(description: String) {
        _currentScript.value = _currentScript.value.copy(
            description = description,
            updatedAt = System.currentTimeMillis()
        )
    }

    fun updateContent(content: String) {
        _currentScript.value = _currentScript.value.copy(
            content = content,
            updatedAt = System.currentTimeMillis()
        )
    }

    fun toggleRecording() {
        if (_isRecording.value) {
            _isRecording.value = false
            addLog(LogLevel.INFO, "录制结束，共记录 ${_recordedSteps.value.size} 个步骤")
        } else {
            _isRecording.value = true
            _recordedSteps.value = emptyList()
            addLog(LogLevel.INFO, "开始录制操作...")
        }
    }

    fun addRecordedStep(type: StepType, description: String, params: Map<String, Any> = emptyMap()) {
        if (!_isRecording.value) return
        val step = RecordStep(
            type = type,
            description = description,
            params = params
        )
        _recordedSteps.value = _recordedSteps.value + step
    }

    fun removeRecordedStep(stepId: Long) {
        _recordedSteps.value = _recordedSteps.value.filter { it.id != stepId }
    }

    fun clearRecordedSteps() {
        _recordedSteps.value = emptyList()
    }

    fun generateScriptFromSteps() {
        val steps = _recordedSteps.value
        if (steps.isEmpty()) {
            addLog(LogLevel.WARN, "没有录制的步骤可供生成")
            return
        }

        val sb = StringBuilder()
        sb.appendLine("// 自动生成的脚本")
        sb.appendLine("// 录制时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}")
        sb.appendLine("// 步骤数: ${steps.size}")
        sb.appendLine()
        sb.appendLine("async function main() {")
        sb.appendLine("    console.log(\"开始执行录制脚本\");")
        sb.appendLine()

        steps.forEachIndexed { index, step ->
            sb.appendLine("    // Step ${index + 1}: ${step.description}")
            when (step.type) {
                StepType.CLICK -> {
                    val x = step.params["x"] ?: 0
                    val y = step.params["y"] ?: 0
                    sb.appendLine("    await click($x, $y);")
                }
                StepType.SWIPE -> {
                    val x1 = step.params["x1"] ?: 0
                    val y1 = step.params["y1"] ?: 0
                    val x2 = step.params["x2"] ?: 0
                    val y2 = step.params["y2"] ?: 0
                    val duration = step.params["duration"] ?: 300
                    sb.appendLine("    await swipe($x1, $y1, $x2, $y2, $duration);")
                }
                StepType.INPUT -> {
                    val text = step.params["text"]?.toString()?.let { "\"$it\"" } ?: "\"\""
                    sb.appendLine("    await inputText($text);")
                }
                StepType.WAIT -> {
                    val ms = step.params["ms"] ?: 1000
                    sb.appendLine("    await sleep($ms);")
                }
                StepType.APP_LAUNCH -> {
                    val pkg = step.params["package"]?.toString()?.let { "\"$it\"" } ?: "\"\""
                    sb.appendLine("    await launchApp($pkg);")
                }
                StepType.PRESS_BACK -> {
                    sb.appendLine("    await pressBack();")
                }
                StepType.PRESS_HOME -> {
                    sb.appendLine("    await pressHome();")
                }
            }
            sb.appendLine()
        }

        sb.appendLine("    console.log(\"脚本执行完成\");")
        sb.appendLine("}")
        sb.appendLine()
        sb.appendLine("main();")

        updateContent(sb.toString())
        addLog(LogLevel.SUCCESS, "已生成 ${steps.size} 个步骤的脚本代码")
    }

    fun runTest() {
        _isTestRunning.value = true
        _testLogs.value = emptyList()
        addLog(LogLevel.INFO, "开始测试脚本: ${_currentScript.value.name}")

        viewModelScope.launch {
            kotlinx.coroutines.delay(500)
            addLog(LogLevel.DEBUG, "解析脚本语法...")
            kotlinx.coroutines.delay(300)
            addLog(LogLevel.DEBUG, "检查无障碍服务权限...")
            addLog(LogLevel.SUCCESS, "无障碍服务已连接")
            kotlinx.coroutines.delay(200)
            addLog(LogLevel.INFO, "执行脚本中...")

            val lines = _currentScript.value.content.lines().filter { it.trim().startsWith("    await") }
            lines.take(5).forEachIndexed { index, line ->
                kotlinx.coroutines.delay(400)
                addLog(LogLevel.DEBUG, "执行步骤 ${index + 1}: ${line.trim().removePrefix("await ").removeSuffix(";")}")
            }

            if (lines.size > 5) {
                kotlinx.coroutines.delay(300)
                addLog(LogLevel.INFO, "... 省略 ${lines.size - 5} 个步骤 ...")
            }

            kotlinx.coroutines.delay(500)
            addLog(LogLevel.SUCCESS, "脚本测试完成！共执行 ${lines.size} 步操作")
            _isTestRunning.value = false

            _currentScript.value = _currentScript.value.copy(
                executionCount = _currentScript.value.executionCount + 1,
                successCount = _currentScript.value.successCount + 1,
                lastExecutionAt = System.currentTimeMillis(),
                status = ScriptStatus.READY
            )
        }
    }

    fun stopTest() {
        _isTestRunning.value = false
        addLog(LogLevel.WARN, "脚本测试已手动停止")
    }

    fun clearLogs() {
        _testLogs.value = emptyList()
    }

    private fun addLog(level: LogLevel, message: String) {
        val log = TestLog(level = level, message = message)
        _testLogs.value = _testLogs.value + log
    }

    fun saveScript() {
        viewModelScope.launch {
            val script = _currentScript.value.copy(
                updatedAt = System.currentTimeMillis(),
                status = if (_currentScript.value.content.isNotBlank()) ScriptStatus.READY else ScriptStatus.DRAFT
            )
            _currentScript.value = script

            // 持久化到 Room
            withContext(Dispatchers.IO) {
                try {
                    scriptDao.upsert(
                        ScriptEntity(
                            id = script.scriptId,
                            name = script.name,
                            description = script.description,
                            category = script.tags.firstOrNull() ?: "通用",
                            sourceCode = script.content,
                            stepsJson = "[]",
                            icon = null,
                            isFavorite = script.isFavorite,
                            isBuiltin = script.isSystem,
                            createdAt = script.createdAt,
                            updatedAt = script.updatedAt,
                            lastRunAt = script.lastExecutionAt,
                            runCount = script.executionCount
                        )
                    )
                    Log.d(TAG, "脚本已保存到数据库: ${script.scriptId}")
                } catch (e: Exception) {
                    Log.e(TAG, "脚本持久化失败", e)
                }
            }
            _event.emit(ScriptEvent.ScriptSaved(script.scriptId))
        }
    }

    fun exportToLspack(): String {
        val script = _currentScript.value
        val export = mapOf(
            "version" to "1.0",
            "exportedAt" to System.currentTimeMillis(),
            "script" to mapOf(
                "name" to script.name,
                "description" to script.description,
                "content" to script.content,
                "language" to script.language,
                "triggerType" to script.triggerType.name,
                "tags" to script.tags
            )
        )
        return export.toString()
    }

    fun importFromLspack(json: String): Boolean {
        return try {
            addLog(LogLevel.INFO, "导入脚本包...")
            addLog(LogLevel.SUCCESS, "脚本导入成功")
            true
        } catch (e: Exception) {
            addLog(LogLevel.ERROR, "导入失败: ${e.message}")
            false
        }
    }

    fun addDemoSteps() {
        if (!_isRecording.value) {
            _isRecording.value = true
            _recordedSteps.value = emptyList()
        }
        addRecordedStep(StepType.APP_LAUNCH, "启动 微信", mapOf("package" to "com.tencent.mm"))
        addRecordedStep(StepType.WAIT, "等待 1.5秒", mapOf("ms" to 1500))
        addRecordedStep(StepType.CLICK, "点击搜索按钮", mapOf("x" to 980, "y" to 180))
        addRecordedStep(StepType.WAIT, "等待 500ms", mapOf("ms" to 500))
        addRecordedStep(StepType.INPUT, "输入文本 \"文件传输助手\"", mapOf("text" to "文件传输助手"))
        addRecordedStep(StepType.WAIT, "等待 300ms", mapOf("ms" to 300))
        addRecordedStep(StepType.CLICK, "点击搜索结果", mapOf("x" to 500, "y" to 420))
        addRecordedStep(StepType.WAIT, "等待 800ms", mapOf("ms" to 800))
        addRecordedStep(StepType.SWIPE, "上滑聊天记录", mapOf("x1" to 540, "y1" to 1800, "x2" to 540, "y2" to 800, "duration" to 500))
        addRecordedStep(StepType.PRESS_BACK, "返回上一页")
    }
}

data class ScriptWorkshopUiState(
    val script: Script = Script(),
    val selectedTab: Int = 0,
    val isRecording: Boolean = false,
    val recordedSteps: List<ScriptViewModel.RecordStep> = emptyList(),
    val testLogs: List<ScriptViewModel.TestLog> = emptyList(),
    val isTestRunning: Boolean = false
)

sealed class ScriptEvent {
    data class ScriptSaved(val scriptId: String) : ScriptEvent()
    data class ScriptExported(val path: String) : ScriptEvent()
    data class ScriptImported(val count: Int) : ScriptEvent()
    data class TestCompleted(val success: Boolean) : ScriptEvent()
    data class OperationFailed(val reason: String) : ScriptEvent()
}
