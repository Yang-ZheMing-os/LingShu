package com.lingshu.agent.feature.control

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lingshu.agent.services.LingShuAccessibilityService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 控制模块 ViewModel
 *
 * 整合以下控制器，统一对外暴露UI层可调用的API与状态：
 * - SystemController        系统级控制（WiFi/蓝牙/音量/亮度/启动App...）
 * - AccessibilityController 无障碍控制（手势/控件/文本输入/按键...）
 * - ScreenCaptureManager    截屏与OCR
 * - ScreenUnderstanding     屏幕理解（VLM视觉分析）
 * - ScriptEngine            脚本引擎（执行/录制/.lspack导入导出）
 *
 * UI层（Activity/Fragment/Compose）只与此 ViewModel 交互，
 * 不直接访问底层控制器。
 */
@HiltViewModel
class ControlViewModel @Inject constructor(
    application: Application,
    val systemController: SystemController,
    val accessibilityController: AccessibilityController,
    val screenCaptureManager: ScreenCaptureManager,
    val screenUnderstanding: ScreenUnderstanding,
    val scriptEngine: ScriptEngine,
    val quickCommandsManager: QuickCommandsManager,
    val scenePresetsManager: ScenePresetsManager,
    val teachingModeManager: TeachingModeManager
) : AndroidViewModel(application) {

    // ==================== 模块7：文本指令控制 ====================

    private val deviceController = DeviceController(getApplication())

    /**
     * 是否检测到控制指令前缀（/控制 / 控制：/ 帮我打开 等）
     */
    fun isControlCommand(text: String): Boolean {
        val trimmed = text.trim()
        return when {
            trimmed.startsWith("/控制") -> true
            trimmed.startsWith("控制：") -> true
            trimmed.startsWith("帮我打开") -> true
            trimmed == "返回" || trimmed == "退出" || trimmed == "回退" -> true
            trimmed == "主页" || trimmed == "桌面" -> true
            trimmed == "最近任务" || trimmed == "多任务" -> true
            trimmed == "通知" || trimmed == "通知栏" || trimmed == "下拉通知" -> true
            trimmed == "截图" -> true
            trimmed.startsWith("打开") || trimmed.startsWith("启动") -> true
            trimmed.startsWith("点击") -> true
            trimmed.startsWith("输入") -> true
            trimmed.startsWith("滑动") || trimmed == "向上滑" || trimmed == "向下滑" ||
                trimmed == "向左滑" || trimmed == "向右滑" -> true
            else -> false
        }
    }

    /**
     * 执行控制指令，返回 DeviceActionResult
     */
    fun processControlCommand(text: String): DeviceActionResult {
        val cleaned = text.trim()
            .removePrefix("/控制")
            .removePrefix("控制：")
            .removePrefix("帮我打开")
            .trim()

        if (cleaned.isEmpty()) {
            return DeviceActionResult(
                success = false,
                action = "空指令",
                message = "请输入控制指令。支持：打开XX / 返回 / 主页 / 滑动 / 点击XX / 输入XX / 截图"
            )
        }

        return deviceController.execute(cleaned)
    }

    // ==================== UI 状态 ====================

    /** 无障碍服务连接状态 */
    private val _accessibilityConnected = MutableStateFlow(
        LingShuAccessibilityService.isConnected()
    )
    val accessibilityConnected: StateFlow<Boolean> =
        _accessibilityConnected.asStateFlow()

    /** MediaProjection 是否就绪 */
    private val _captureReady = MutableStateFlow(screenCaptureManager.isReady())
    val captureReady: StateFlow<Boolean> = _captureReady.asStateFlow()

    /** OCR引擎是否可用 */
    private val _ocrAvailable = MutableStateFlow(screenCaptureManager.isOcrAvailable())
    val ocrAvailable: StateFlow<Boolean> = _ocrAvailable.asStateFlow()

    /** VLM引擎是否可用 */
    private val _vlmAvailable = MutableStateFlow(screenUnderstanding.isVlmAvailable())
    val vlmAvailable: StateFlow<Boolean> = _vlmAvailable.asStateFlow()

    /** 脚本执行状态 */
    val scriptExecutionStatus: StateFlow<ScriptEngine.ExecutionStatus> =
        scriptEngine.status

    /** 脚本录制状态 */
    val scriptRecording: StateFlow<Boolean> = scriptEngine.isRecordingFlow

    /** 系统设置快照（用于UI显示） */
    data class SystemSettingsSnapshot(
        val wifiEnabled: Boolean,
        val bluetoothEnabled: Boolean?,
        val flashlightEnabled: Boolean,
        val nfcEnabled: Boolean?,
        val nfcSupported: Boolean,
        val autoRotateEnabled: Boolean,
        val brightness: Int?,
        val volumeMusic: Int,
        val volumeRing: Int,
        val volumeNotification: Int,
        val volumeAlarm: Int
    )

    private val _systemSettings = MutableStateFlow(
        SystemSettingsSnapshot(
            wifiEnabled = false,
            bluetoothEnabled = null,
            flashlightEnabled = false,
            nfcEnabled = null,
            nfcSupported = false,
            autoRotateEnabled = false,
            brightness = null,
            volumeMusic = 0,
            volumeRing = 0,
            volumeNotification = 0,
            volumeAlarm = 0
        )
    )
    val systemSettings: StateFlow<SystemSettingsSnapshot> =
        _systemSettings.asStateFlow()

    /** 通用消息流（Toast/Snackbar用） */
    private val _messages = MutableSharedFlow<String>()
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** 通用加载状态 */
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    // ==================== 初始化与清理 ====================

    private var serviceConnectionListener: LingShuAccessibilityService.Companion.ConnectionListener? = null
    private var capturePollJob: Job? = null

    init {
        // 监听无障碍服务连接状态
        val listener = object : LingShuAccessibilityService.Companion.ConnectionListener {
            override fun onConnected() {
                _accessibilityConnected.value = true
            }

            override fun onDisconnected() {
                _accessibilityConnected.value = false
            }
        }
        serviceConnectionListener = listener
        LingShuAccessibilityService.registerListener(listener)

        // 初次刷新系统状态
        refreshSystemSettings()
    }

    override fun onCleared() {
        serviceConnectionListener?.let {
            LingShuAccessibilityService.unregisterListener(it)
        }
        capturePollJob?.cancel()
        super.onCleared()
    }

    // ==================== 辅助方法 ====================

    /**
     * 发送一条UI消息（Toast/Snackbar）
     */
    private fun postMessage(msg: String) {
        viewModelScope.launch {
            _messages.emit(msg)
        }
    }

    /**
     * 刷新系统设置快照（手动调用）
     */
    fun refreshSystemSettings() {
        _systemSettings.value = SystemSettingsSnapshot(
            wifiEnabled = runCatching { systemController.isWifiEnabled() }
                .getOrDefault(false),
            bluetoothEnabled = systemController.isBluetoothEnabled(),
            flashlightEnabled = systemController.isFlashlightEnabled(),
            nfcEnabled = systemController.isNfcEnabled(),
            nfcSupported = systemController.isNfcSupported(),
            autoRotateEnabled = systemController.isAutoRotateEnabled(),
            brightness = systemController.getBrightness(),
            volumeMusic = systemController.getVolume(
                VolumeType.MUSIC
            ),
            volumeRing = systemController.getVolume(
                VolumeType.RING
            ),
            volumeNotification = systemController.getVolume(
                VolumeType.NOTIFICATION
            ),
            volumeAlarm = systemController.getVolume(
                VolumeType.ALARM
            )
        )
    }

    // ==================== 系统控制快捷方法 ====================

    fun toggleWifi() {
        viewModelScope.launch {
            val current = _systemSettings.value.wifiEnabled
            val result = systemController.setWifiEnabled(!current)
            postMessage(result.message ?: (if (!current) "WiFi已开启" else "WiFi已关闭"))
            refreshSystemSettings()
        }
    }

    fun toggleBluetooth() {
        viewModelScope.launch {
            val current = _systemSettings.value.bluetoothEnabled ?: false
            val result = systemController.setBluetoothEnabled(!current)
            postMessage(result.message ?: (if (!current) "蓝牙已开启" else "蓝牙已关闭"))
            refreshSystemSettings()
        }
    }

    fun toggleFlashlight() {
        viewModelScope.launch {
            val current = _systemSettings.value.flashlightEnabled
            val result = systemController.setFlashlightEnabled(!current)
            postMessage(result.message ?: (if (!current) "手电筒已开启" else "手电筒已关闭"))
            refreshSystemSettings()
        }
    }

    fun toggleAutoRotate() {
        viewModelScope.launch {
            val current = _systemSettings.value.autoRotateEnabled
            val result = systemController.setAutoRotateEnabled(!current)
            postMessage(result.message ?: "需要WRITE_SETTINGS权限")
            refreshSystemSettings()
        }
    }

    fun setBrightnessVM(value: Int) {
        viewModelScope.launch {
            val result = systemController.setBrightness(value)
            if (!result.success) {
                postMessage(result.message ?: "需要WRITE_SETTINGS权限")
            }
            refreshSystemSettings()
        }
    }

    fun setVolumeVM(type: VolumeType, value: Int, showUI: Boolean = false) {
        viewModelScope.launch {
            val result = systemController.setVolume(type, value, showUI)
            if (!result.success) {
                postMessage(result.message ?: "设置音量失败")
            }
            refreshSystemSettings()
        }
    }

    fun launchApp(packageName: String) {
        viewModelScope.launch {
            val result = systemController.launchApp(packageName)
            postMessage(result.message ?: "正在启动应用...")
        }
    }

    // ==================== 无障碍控制 ====================

    /** 点击坐标 */
    fun clickAt(x: Float, y: Float, onResult: (Boolean) -> Unit = {}) {
        if (!_accessibilityConnected.value) {
            postMessage("请先开启无障碍服务")
            onResult(false)
            return
        }
        accessibilityController.click(x, y, 50L,
            object : AccessibilityController.GestureResultCallback {
                override fun onSuccess() {
                    postMessage("点击 ($x, $y)")
                    onResult(true)
                }

                override fun onFailure(reason: String) {
                    postMessage("点击失败: $reason")
                    onResult(false)
                }
            })
    }

    /** 滑动 */
    fun swipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long = 300L,
        onResult: (Boolean) -> Unit = {}
    ) {
        if (!_accessibilityConnected.value) {
            postMessage("请先开启无障碍服务")
            onResult(false)
            return
        }
        accessibilityController.swipe(startX, startY, endX, endY, durationMs,
            object : AccessibilityController.GestureResultCallback {
                override fun onSuccess() {
                    onResult(true)
                }

                override fun onFailure(reason: String) {
                    postMessage("滑动失败: $reason")
                    onResult(false)
                }
            })
    }

    fun pressBack() {
        val ok = accessibilityController.pressBack()
        if (!ok) postMessage("请先开启无障碍服务")
    }

    fun pressHome() {
        val ok = accessibilityController.pressHome()
        if (!ok) postMessage("请先开启无障碍服务")
    }

    fun pressRecents() {
        val ok = accessibilityController.pressRecentApps()
        if (!ok) postMessage("请先开启无障碍服务")
    }

    fun clickByText(text: String) {
        val ok = accessibilityController.clickByText(text)
        postMessage(if (ok) "已点击文本: $text" else "未找到控件: $text")
    }

    fun clickById(viewId: String) {
        val ok = accessibilityController.clickById(viewId)
        postMessage(if (ok) "已点击控件: $viewId" else "未找到控件ID: $viewId")
    }

    fun inputText(text: String) {
        val ok = accessibilityController.inputText(text)
        postMessage(if (ok) "已输入文本" else "输入失败，请确保输入框已聚焦")
    }

    /** 获取当前控件树（转换为纯数据） */
    fun getCurrentNodeTree(): AccessibilityController.NodeInfo? {
        return accessibilityController.getAccessibilityNodeTree()
    }

    // ==================== 截屏 & OCR ====================

    /**
     * 初始化MediaProjection（在授权后调用）
     */
    fun initializeCapture(resultCode: Int, resultData: Intent): Boolean {
        val ok = screenCaptureManager.initialize(resultCode, resultData)
        _captureReady.value = screenCaptureManager.isReady()
        return ok
    }

    /** 获取 MediaProjection 授权 Intent */
    fun getScreenCaptureIntent(): Intent {
        return screenCaptureManager.createScreenCaptureIntent()
    }

    /**
     * 截图（回调返回Bitmap）
     */
    fun captureScreen(onResult: (Result<Bitmap>) -> Unit) {
        if (!_captureReady.value) {
            postMessage("请先授予截屏权限")
            onResult(Result.failure(IllegalStateException("未初始化截屏")))
            return
        }
        screenCaptureManager.captureScreen(
            object : ScreenCaptureManager.CaptureCallback {
                override fun onSuccess(bitmap: Bitmap) {
                    onResult(Result.success(bitmap))
                }

                override fun onFailure(reason: String) {
                    postMessage("截屏失败: $reason")
                    onResult(Result.failure(Exception(reason)))
                }
            }
        )
    }

    /**
     * 截图 + OCR
     */
    fun captureAndOcr(onResult: (Result<ScreenCaptureManager.OcrResult>) -> Unit) {
        if (!_captureReady.value) {
            postMessage("请先授予截屏权限")
            onResult(Result.failure(IllegalStateException("未初始化截屏")))
            return
        }
        if (!_ocrAvailable.value) {
            postMessage("OCR引擎不可用")
            onResult(Result.failure(IllegalStateException("OCR引擎未设置")))
            return
        }
        _loading.value = true
        screenCaptureManager.captureAndOcr { result ->
            _loading.value = false
            onResult(result)
        }
    }

    // ==================== 屏幕理解（VLM） ====================

    /**
     * 截屏 + VLM 分析
     * @param prompt 用户提示词
     * @param systemPrompt 系统提示词
     * @param useOcr 是否附带OCR文本辅助
     */
    fun analyzeScreen(
        prompt: String = "请描述屏幕上的内容",
        systemPrompt: String = ScreenUnderstanding.SYSTEM_PROMPT_DEFAULT,
        useOcr: Boolean = true,
        onResult: (Result<ScreenUnderstanding.VlmResult>) -> Unit
    ) {
        if (!_captureReady.value) {
            postMessage("请先授予截屏权限")
            onResult(Result.failure(IllegalStateException("未初始化截屏")))
            return
        }
        if (!_vlmAvailable.value) {
            postMessage("VLM引擎未配置")
            onResult(Result.failure(IllegalStateException("VLM引擎不可用")))
            return
        }

        _loading.value = true
        viewModelScope.launch {
            val result = screenUnderstanding.captureAndAnalyze(
                prompt = prompt,
                systemPrompt = systemPrompt,
                useOcrFirst = useOcr
            )
            _loading.value = false
            onResult(result)
        }
    }

    /**
     * VLM 操作指引：给定目标，分析当前屏幕并给出下一步操作建议
     */
    fun guideAction(userGoal: String, onResult: (Result<ScreenUnderstanding.VlmResult>) -> Unit) {
        if (!_captureReady.value) {
            postMessage("请先授予截屏权限")
            onResult(Result.failure(IllegalStateException("未初始化截屏")))
            return
        }
        if (!_vlmAvailable.value) {
            postMessage("VLM引擎未配置")
            onResult(Result.failure(IllegalStateException("VLM引擎不可用")))
            return
        }
        _loading.value = true
        viewModelScope.launch {
            val result = screenUnderstanding.analyzeAndGuide(userGoal)
            _loading.value = false
            onResult(result)
        }
    }

    // ==================== 脚本引擎 ====================

    /**
     * 执行脚本
     */
    fun executeScript(script: String, name: String = "unnamed") {
        scriptEngine.execute(script, name)
    }

    /**
     * 停止脚本
     */
    fun stopScript() {
        scriptEngine.stop()
        postMessage("脚本已停止")
    }

    /**
     * 开始录制脚本
     */
    fun startRecording() {
        scriptEngine.startRecording()
        postMessage("开始录制操作...")
    }

    /**
     * 停止录制，返回生成的脚本源码
     */
    fun stopRecording(): String {
        val script = scriptEngine.stopRecording()
        postMessage("录制完成")
        return script
    }

    /**
     * 导出脚本包
     */
    fun exportScriptPackage(
        pkg: ScriptEngine.ScriptPackage,
        outputFile: java.io.File,
        onResult: (Boolean) -> Unit
    ) {
        viewModelScope.launch {
            val ok = scriptEngine.exportScriptPackage(pkg, outputFile)
            postMessage(if (ok) "导出成功" else "导出失败")
            onResult(ok)
        }
    }

    /**
     * 导入脚本包
     */
    fun importScriptPackage(
        file: java.io.File,
        onResult: (ScriptEngine.ScriptPackage?) -> Unit
    ) {
        viewModelScope.launch {
            val result = scriptEngine.importScriptPackage(file)
            postMessage(if (result != null) "导入成功" else "导入失败")
            onResult(result)
        }
    }

    // ==================== P3: 快捷指令 ====================

    /** 快捷指令加载状态 */
    private val _quickCommands = MutableStateFlow<List<QuickCommand>>(emptyList())
    val quickCommands: StateFlow<List<QuickCommand>> = _quickCommands.asStateFlow()

    /** 命令执行结果消息 */
    private val _commandResults = MutableSharedFlow<DeviceActionResult>()
    val commandResults: SharedFlow<DeviceActionResult> = _commandResults.asSharedFlow()

    /**
     * 加载全部快捷指令（预设 + 自定义，按使用次数降序）
     */
    fun loadQuickCommands() {
        viewModelScope.launch {
            try {
                val all = quickCommandsManager.getAllCommands()
                _quickCommands.value = all
            } catch (e: Exception) {
                postMessage("加载快捷指令失败: ${e.message}")
            }
        }
    }

    /**
     * 执行单个快捷指令
     */
    fun executeQuickCommand(command: QuickCommand) {
        viewModelScope.launch {
            try {
                // 增加使用计数
                quickCommandsManager.incrementUsage(command.name)
                // 执行每个动作
                for (action in command.actions) {
                    val result = when (action.type) {
                        "system" -> quickCommandsManager.executeSystemCommand(
                            systemController, accessibilityController,
                            action.text ?: ""
                        )
                        "launch" -> systemController.launchApp(action.packageName ?: "")
                        else -> DeviceActionResult(false, action.type, "暂不支持的操作类型")
                    }
                    _commandResults.emit(result)
                }
                refreshSystemSettings()
                loadQuickCommands()
            } catch (e: Exception) {
                postMessage("执行指令失败: ${e.message}")
            }
        }
    }

    // ==================== P3: 场景预设 ====================

    /** 当前激活场景 */
    private val _currentScene = MutableStateFlow<ScenePreset?>(null)
    val currentScene: StateFlow<ScenePreset?> = _currentScene.asStateFlow()

    fun loadCurrentScene() {
        viewModelScope.launch {
            val scene = scenePresetsManager.getCurrentScene()
            _currentScene.value = scene
        }
    }

    /** 一键应用场景 */
    fun applyScene(scene: ScenePreset) {
        viewModelScope.launch {
            _loading.value = true
            try {
                val results = scenePresetsManager.applyScene(scene, systemController)
                _currentScene.value = scene
                refreshSystemSettings()
                loadQuickCommands()
                results.forEach { _commandResults.emit(it) }
                postMessage("场景「${scene.name}」已应用")
            } catch (e: Exception) {
                postMessage("应用场景失败: ${e.message}")
            } finally {
                _loading.value = false
            }
        }
    }

    // ==================== P4: 教学模式 ====================

    /** 教学模式状态 */
    val teachingState: StateFlow<TeachingState> = teachingModeManager.teachingState

    /** 录制操作列表 */
    val recordedActions: StateFlow<List<ActionRecord>> = teachingModeManager.recordedActions

    /** 教学模式事件 */
    val teachingEvents: SharedFlow<TeachingEvent> = teachingModeManager.teachingEvents

    companion object {
        /** 教学模式触发关键词 */
        val TEACHING_TRIGGER_WORDS = listOf(
            "教我", "演示一下", "教我一下", "怎么操作",
            "示范", "录制", "记录操作", "学一下", "教我怎么"
        )
    }

    /**
     * 检测是否为教学模式触发指令
     */
    fun isTeachingTrigger(text: String): Boolean {
        val trimmed = text.trim()
        return TEACHING_TRIGGER_WORDS.any { trimmed.contains(it) }
    }

    /**
     * 开始录制操作
     */
    fun startTeachingRecording() {
        viewModelScope.launch {
            teachingModeManager.teachingEvents.collect { event ->
                when (event) {
                    is TeachingEvent.RecordingStarted -> postMessage("请开始操作，我会记录你的步骤")
                    is TeachingEvent.RecordingCompleted -> {
                        postMessage("录制完成！共 ${event.actionCount} 步，已保存")
                    }
                    is TeachingEvent.RecordingCancelled -> postMessage("录制已取消")
                    is TeachingEvent.Error -> postMessage(event.message)
                    is TeachingEvent.NeedAccessibilityGuide -> {
                        postMessage("无障碍服务未开启，正在跳转设置...")
                    }
                }
            }
        }
        viewModelScope.launch {
            teachingModeManager.startRecording()
        }
    }

    /**
     * 停止录制并保存
     */
    fun stopTeachingRecording(skillName: String) {
        viewModelScope.launch {
            try {
                val script = teachingModeManager.stopRecording(skillName)
                postMessage("已保存技能脚本「$skillName」")
            } catch (e: Exception) {
                postMessage("停止录制失败: ${e.message}")
            }
        }
    }

    /**
     * 取消录制
     */
    fun cancelTeachingRecording() {
        viewModelScope.launch {
            teachingModeManager.cancelRecording()
        }
    }

    /**
     * 加载已保存的技能脚本
     */
    fun loadSavedSkillScripts(): List<SkillScript> {
        return teachingModeManager.loadSavedScripts()
    }

    // ==================== 无障碍服务自检 ====================

    /**
     * 检查无障碍服务并在断开时生成引导 Intent
     */
    fun checkAccessibilityAndGetGuide(): Pair<Boolean, Intent?> {
        val service = LingShuAccessibilityService.instance
        if (service != null) {
            val (connected, guideIntent) = service.checkAndGetGuide()
            return Pair(connected, guideIntent)
        }
        val wasDisabled = LingShuAccessibilityService.Companion::class.java
            .getDeclaredField("wasDisabled").apply { isAccessible = true }
            .getBoolean(LingShuAccessibilityService.Companion)
        return Pair(false, if (wasDisabled) LingShuAccessibilityService.generateEnableGuideIntent() else null)
    }

    /**
     * 刷新能力状态（在UI层onResume时调用）
     */
    fun refreshCapabilities() {
        _accessibilityConnected.value = LingShuAccessibilityService.isConnected()
        _captureReady.value = screenCaptureManager.isReady()
        _ocrAvailable.value = screenCaptureManager.isOcrAvailable()
        _vlmAvailable.value = screenUnderstanding.isVlmAvailable()
        refreshSystemSettings()
        loadQuickCommands()
        loadCurrentScene()
    }
}
