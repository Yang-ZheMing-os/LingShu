package com.lingshu.agent.feature.control

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.lingshu.agent.services.LingShuAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import com.google.gson.Gson
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 教学模式 — 操作记录数据结构
 */
data class ActionRecord(
    val actionType: String,    // "click", "long_press", "swipe", "input", "scroll"
    val target: String? = null, // 目标控件文本/ID描述
    val x: Float? = null,
    val y: Float? = null,
    val endX: Float? = null,
    val endY: Float? = null,
    val text: String? = null,  // 输入文本
    val timestamp: Long = SystemClock.uptimeMillis()
)

/**
 * 教学模式录制的技能脚本
 */
data class SkillScript(
    val name: String,
    val createdAt: Long,
    val actions: List<ActionRecord>,
    val metadata: ScriptMetadata = ScriptMetadata()
)

data class ScriptMetadata(
    val source: String = "teaching_mode",
    val version: Int = 1,
    val recordedApp: String? = null,
    val duration: Long = 0 // 录制总耗时(ms)
)

/**
 * 教学模式状态
 */
enum class TeachingState {
    IDLE,       // 空闲
    RECORDING,  // 录制中
    SAVING      // 保存中
}

/**
 * 教学模式管理器 — P4 规格书对标。
 * 用户说"教我/演示一下"触发录制，操作保存为 SkillScript。
 */
@Singleton
class TeachingModeManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "TeachingMode"
        /** 最大录制步数 */
        const val MAX_STEPS = 50
        /** 最长录制时间（毫秒） */
        const val MAX_DURATION_MS = 5 * 60 * 1000L // 5分钟
        /** 技能脚本存储目录 */
        private const val SKILL_SCRIPTS_DIR = "skill_scripts"
    }

    private val gson = Gson()

    private val _teachingState = MutableStateFlow(TeachingState.IDLE)
    val teachingState: StateFlow<TeachingState> = _teachingState.asStateFlow()

    private val _recordedActions = MutableStateFlow<List<ActionRecord>>(emptyList())
    val recordedActions: StateFlow<List<ActionRecord>> = _recordedActions.asStateFlow()

    private val _teachingEvents = MutableSharedFlow<TeachingEvent>()
    val teachingEvents: SharedFlow<TeachingEvent> = _teachingEvents.asSharedFlow()

    // 录制控制
    private var recordingJob: Job? = null
    private var recordedApp: String? = null
    private val actions = mutableListOf<ActionRecord>()

    /** 录制开始时间（SystemClock.uptimeMillis） */
    private var recordingStartTime: Long = 0L

    /** 是否正在录制 */
    val isRecording: Boolean get() = _teachingState.value == TeachingState.RECORDING

    /**
     * 开始录制
     */
    suspend fun startRecording() {
        if (isRecording) return
        val service = LingShuAccessibilityService.instance
        if (service == null) {
            _teachingEvents.emit(TeachingEvent.Error("无障碍服务未开启，请先前往设置 → 无障碍 开启灵枢服务"))
            return
        }

        // 检查无障碍连接
        val (connected, guideIntent) = service.checkAndGetGuide()
        if (!connected && guideIntent != null) {
            _teachingEvents.emit(TeachingEvent.NeedAccessibilityGuide(guideIntent))
            return
        }

        actions.clear()
        recordedApp = service.currentPackageName
        recordingStartTime = SystemClock.uptimeMillis()
        _teachingState.value = TeachingState.RECORDING
        _recordedActions.value = emptyList()

        _teachingEvents.emit(TeachingEvent.RecordingStarted)
        Log.i(TAG, "录制开始 — 当前应用：$recordedApp")

        // 注册事件监听
        service.addEventListener(accessibilityEventListener)
    }

    /**
     * 结束录制并保存为技能脚本
     */
    suspend fun stopRecording(skillName: String): SkillScript {
        if (!isRecording) throw IllegalStateException("当前未在录制")

        val service = LingShuAccessibilityService.instance
        service?.removeEventListener(accessibilityEventListener)

        // 时间检查
        val now = SystemClock.uptimeMillis()
        val duration = now - recordingStartTime

        _teachingState.value = TeachingState.SAVING

        val script = SkillScript(
            name = skillName,
            createdAt = System.currentTimeMillis(),
            actions = actions.toList(),
            metadata = ScriptMetadata(
                source = "teaching_mode",
                version = 1,
                recordedApp = recordedApp,
                duration = duration
            )
        )

        // 持久化到 skill_scripts 目录
        val scriptsDir = File(context.filesDir, SKILL_SCRIPTS_DIR)
        if (!scriptsDir.exists()) scriptsDir.mkdirs()
        val fileName = "${skillName.replace(Regex("[\\\\/:*?\"<>|]"), "_")}_${System.currentTimeMillis()}.json"
        val file = File(scriptsDir, fileName)
        file.writeText(gson.toJson(script))

        _teachingState.value = TeachingState.IDLE
        _recordedActions.value = actions.toList()

        _teachingEvents.emit(
            TeachingEvent.RecordingCompleted(
                actionCount = actions.size,
                duration = duration,
                savedPath = file.absolutePath
            )
        )

        Log.i(TAG, "录制完成 — 步数=${actions.size}, 耗时=${duration}ms, 文件=$fileName")
        return script
    }

    /**
     * 取消录制（不保存）
     */
    suspend fun cancelRecording() {
        if (!isRecording) return
        val service = LingShuAccessibilityService.instance
        service?.removeEventListener(accessibilityEventListener)
        actions.clear()
        _teachingState.value = TeachingState.IDLE
        _recordedActions.value = emptyList()
        _teachingEvents.emit(TeachingEvent.RecordingCancelled)
    }

    /**
     * 监听无障碍事件，录制操作
     */
    private val accessibilityEventListener = object : LingShuAccessibilityService.EventListener {
        override fun onEvent(
            event: android.view.accessibility.AccessibilityEvent,
            eventType: Int,
            packageName: String?
        ) {
            if (!isRecording) return

            when (eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    recordAction(
                        ActionRecord(
                            actionType = "click",
                            x = null,
                            y = null,
                            target = event.className?.toString()
                        )
                    )
                }
                // 通用触摸事件（记录坐标）
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> {
                    // 仅在结束事件时记录以便去重
                    if (eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_END) return
                }
                // 视图滚动
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                    recordAction(
                        ActionRecord(
                            actionType = "scroll",
                            target = event.className?.toString(),
                            x = null, y = null
                        )
                    )
                }
                // 文本变化（输入）
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    val textBefore = event.beforeText?.toString() ?: ""
                    val textAfter = event.text?.joinToString("") ?: ""
                    if (textBefore.isNotEmpty() || textAfter.isNotEmpty()) {
                        recordAction(
                            ActionRecord(
                                actionType = "input",
                                text = textAfter,
                                target = event.className?.toString()
                            )
                        )
                    }
                }
            }
        }
    }

    private fun recordAction(action: ActionRecord) {
        synchronized(actions) {
            // 步数检查
            if (actions.size >= MAX_STEPS) {
                Log.w(TAG, "已达到最大录制步数 $MAX_STEPS")
                return
            }
            actions.add(action)
            _recordedActions.value = actions.toList()
        }
    }

    /**
     * 加载之前保存的技能脚本
     */
    fun loadSavedScripts(): List<SkillScript> {
        val scriptsDir = File(context.filesDir, SKILL_SCRIPTS_DIR)
        if (!scriptsDir.exists()) return emptyList()
        return scriptsDir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { file ->
                try {
                    gson.fromJson(file.readText(), SkillScript::class.java)
                } catch (e: Exception) {
                    null
                }
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }

    /**
     * 将 SkillScript 转换为 ScriptEngine 兼容的 .lspack 格式
     */
    fun exportToLspack(script: SkillScript): File {
        val scriptsDir = File(context.filesDir, SKILL_SCRIPTS_DIR)
        if (!scriptsDir.exists()) scriptsDir.mkdirs()
        val exportFile = File(scriptsDir, "${script.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")}.lspack")
        val wrapper = mapOf(
            "type" to "skill_script",
            "version" to 1,
            "name" to script.name,
            "createdAt" to script.createdAt,
            "actions" to script.actions.map { action ->
                mapOf(
                    "type" to action.actionType,
                    "target" to (action.target ?: ""),
                    "x" to (action.x ?: 0f),
                    "y" to (action.y ?: 0f),
                    "endX" to (action.endX ?: 0f),
                    "endY" to (action.endY ?: 0f),
                    "text" to (action.text ?: ""),
                    "timestamp" to action.timestamp
                )
            }
        )
        exportFile.writeText(gson.toJson(wrapper))
        return exportFile
    }
}

/**
 * 教学模式事件
 */
sealed class TeachingEvent {
    object RecordingStarted : TeachingEvent()
    object RecordingCancelled : TeachingEvent()
    data class RecordingCompleted(
        val actionCount: Int,
        val duration: Long,
        val savedPath: String
    ) : TeachingEvent()

    data class Error(val message: String) : TeachingEvent()
    data class NeedAccessibilityGuide(val intent: android.content.Intent) : TeachingEvent()
}
