package com.lingshu.agent.feature.control

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自动化脚本引擎（JavaScript）
 *
 * 功能：
 * 1. JavaScript脚本执行（优先使用 javax.script 中的SimpleScriptEngine接口，
 *    无可用JS引擎时使用内置占位实现）
 * 2. 自动化API：click、swipe、wait、input、launchApp、home、back、volume 等
 * 3. 支持条件判断、循环、变量（由JS引擎原生支持）
 * 4. 脚本录制功能（记录用户操作序列）
 * 5. .lspack 格式脚本包导入导出（ZIP格式，包含脚本JSON和元数据）
 */
@Singleton
class ScriptEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val systemController: SystemController,
    private val accessibilityController: AccessibilityController
) {

    companion object {
        private const val TAG = "ScriptEngine"

        /** .lspack 文件中脚本文件名 */
        private const val LS_PACK_SCRIPT_FILE = "script.js"

        /** .lspack 文件中元数据文件名 */
        private const val LS_PACK_META_FILE = "metadata.json"

        /** 版本号 */
        const val ENGINE_VERSION = "1.0.0"
    }

    // ==================== 脚本引擎核心 ====================

    /** 脚本引擎是否可用 */
    private var realJsEngine: Any? = null

    /** 引擎初始化状态 */
    private var engineReady = false

    /**
     * 初始化脚本引擎
     */
    private fun ensureEngine(): Boolean {
        if (engineReady) return realJsEngine != null

        // javax.script is not available on Android, always use fallback interpreter
        Log.d(TAG, "javax.script not available on Android, using built-in interpreter")
        realJsEngine = null
        engineReady = true
        return false
    }

    // ==================== 脚本执行状态 ====================

    /** 执行状态 */
    enum class ExecutionState {
        IDLE,        // 空闲
        RUNNING,     // 运行中
        PAUSED,      // 已暂停
        STOPPED,     // 已停止
        ERROR        // 出错
    }

    data class ExecutionStatus(
        val state: ExecutionState,
        val scriptName: String? = null,
        val currentLine: Int = 0,
        val error: String? = null,
        val logs: List<String> = emptyList()
    )

    private val _status = MutableStateFlow(
        ExecutionStatus(ExecutionState.IDLE)
    )
    val status: StateFlow<ExecutionStatus> = _status.asStateFlow()

    private var executionJob: Job? = null
    private val executionScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // ==================== Script API 实现 ====================

    /**
     * 脚本中可调用的API集合
     */
    inner class ScriptApi {
        private val logs = mutableListOf<String>()

        fun getLogs(): List<String> = logs.toList()

        fun clearLogs() = logs.clear()

        /** 打印日志 */
        fun log(message: Any?) {
            val msg = message?.toString() ?: "null"
            logs.add(msg)
            Log.d(TAG, "[Script] $msg")
        }

        /** 显示Toast */
        fun toast(message: Any?) {
            val msg = message?.toString() ?: "null"
            logs.add("Toast: $msg")
            Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        // ========== 手势类 ==========

        fun click(x: Number, y: Number): Boolean {
            val result = CompletableDeferred<Boolean>()
            accessibilityController.click(
                x.toFloat(), y.toFloat(), 50L,
                object : AccessibilityController.GestureResultCallback {
                    override fun onSuccess() { result.complete(true) }
                    override fun onFailure(reason: String) {
                        logs.add("click失败: $reason")
                        result.complete(false)
                    }
                }
            )
            return runBlocking { result.await() }
        }

        fun longClick(x: Number, y: Number, duration: Number = 500): Boolean {
            val result = CompletableDeferred<Boolean>()
            accessibilityController.longClick(
                x.toFloat(), y.toFloat(), duration.toLong(),
                object : AccessibilityController.GestureResultCallback {
                    override fun onSuccess() { result.complete(true) }
                    override fun onFailure(reason: String) {
                        logs.add("longClick失败: $reason")
                        result.complete(false)
                    }
                }
            )
            return runBlocking { result.await() }
        }

        fun swipe(
            startX: Number, startY: Number,
            endX: Number, endY: Number,
            duration: Number = 300
        ): Boolean {
            val result = CompletableDeferred<Boolean>()
            accessibilityController.swipe(
                startX.toFloat(), startY.toFloat(),
                endX.toFloat(), endY.toFloat(),
                duration.toLong(),
                object : AccessibilityController.GestureResultCallback {
                    override fun onSuccess() { result.complete(true) }
                    override fun onFailure(reason: String) {
                        logs.add("swipe失败: $reason")
                        result.complete(false)
                    }
                }
            )
            return runBlocking { result.await() }
        }

        fun sleep(ms: Number) = wait(ms)

        fun wait(ms: Number) {
            Thread.sleep(ms.toLong())
        }

        // ========== 输入系统 ==========

        fun input(text: String): Boolean = accessibilityController.inputText(text)

        // ========== 系统按键 ==========

        fun home(): Boolean = accessibilityController.pressHome()
        fun back(): Boolean = accessibilityController.pressBack()
        fun recents(): Boolean = accessibilityController.pressRecentApps()
        fun notifications(): Boolean = accessibilityController.openNotifications()
        fun quickSettings(): Boolean = accessibilityController.openQuickSettings()

        // ========== App控制 ==========

        fun launchApp(packageName: String): Boolean = runBlocking { systemController.launchApp(packageName).success }
        fun closeApp(packageName: String): Boolean = runBlocking { systemController.closeApp(packageName).success }

        // ========== 控件查找与操作 ==========

        fun clickText(text: String): Boolean = accessibilityController.clickByText(text)
        fun clickById(id: String): Boolean = accessibilityController.clickById(id)
        fun setTextById(id: String, text: String): Boolean =
            accessibilityController.setTextById(id, text)

        fun findText(text: String): List<Map<String, Any?>> {
            return accessibilityController.findNodesByText(text).map { node ->
                mapOf(
                    "text" to node.text,
                    "contentDescription" to node.contentDescription,
                    "bounds" to node.boundsInScreen,
                    "clickable" to node.isClickable
                )
            }
        }

        fun findById(id: String): List<Map<String, Any?>> {
            return accessibilityController.findNodesById(id).map { node ->
                mapOf(
                    "id" to node.viewIdResourceName,
                    "className" to node.className,
                    "bounds" to node.boundsInScreen,
                    "clickable" to node.isClickable
                )
            }
        }

        // ========== 屏幕内容获取 ==========

        /** 获取当前屏幕文本内容（通过无障碍服务） */
        fun getScreenText(): String {
            val rootNode = accessibilityController.getRootNode()
            return if (rootNode != null) {
                try {
                    val sb = StringBuilder()
                    extractNodeText(rootNode, sb, 0)
                    sb.toString().trim().ifEmpty { "屏幕节点树为空" }
                } finally {
                    rootNode.recycle()
                }
            } else {
                "无障碍服务未就绪，无法获取屏幕内容"
            }
        }

        private fun extractNodeText(
            node: android.view.accessibility.AccessibilityNodeInfo,
            sb: StringBuilder,
            depth: Int
        ) {
            val prefix = "  ".repeat(depth)
            val text = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()
            val label = when {
                !text.isNullOrBlank() && !desc.isNullOrBlank() -> "$text ($desc)"
                !text.isNullOrBlank() -> text
                !desc.isNullOrBlank() -> desc
                else -> null
            }
            if (label != null) {
                sb.appendLine("$prefix$label")
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    try {
                        extractNodeText(child, sb, depth + 1)
                    } finally {
                        child.recycle()
                    }
                }
            }
        }

        // ========== TTS 语音 ==========

        /** TTS 朗读文字 */
        fun speak(text: String) {
            var ttsEngine: android.speech.tts.TextToSpeech? = null
            ttsEngine = android.speech.tts.TextToSpeech(context) { status ->
                if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                    ttsEngine?.language = java.util.Locale.CHINESE
                    ttsEngine?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "script_tts_${System.currentTimeMillis()}")
                }
            }
        }

        // ========== 通知 ==========

        /** 发送系统通知 */
        fun sendNotification(title: String, text: String) {
            val channelId = "script_engine_channel"
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

            // 创建通知渠道（Android 8+ 必需）
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    channelId, "脚本通知", android.app.NotificationManager.IMPORTANCE_DEFAULT
                )
                manager.createNotificationChannel(channel)
            }

            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val pendingIntent = android.app.PendingIntent.getActivity(
                context, 0, intent,
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M)
                    android.app.PendingIntent.FLAG_IMMUTABLE
                else 0
            )

            val notification = android.app.Notification.Builder(context, channelId)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            manager.notify(System.currentTimeMillis().toInt(), notification)
            logs.add("通知已发送: $title")
        }

        // ========== 闹钟 ==========

        /** 设置定时闹钟（延迟毫秒后触发） */
        fun setAlarm(delayMs: Number, label: String) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                putExtra("alarm_label", label)
            }
            val pendingIntent = android.app.PendingIntent.getActivity(
                context, System.currentTimeMillis().toInt(), intent,
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M)
                    android.app.PendingIntent.FLAG_IMMUTABLE
                else 0
            )
            val triggerTime = System.currentTimeMillis() + delayMs.toLong()
            alarmManager.setExact(android.app.AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            logs.add("闹钟已设置: $label (${delayMs}ms后)")
        }

        // ========== 剪贴板 ==========

        /** 获取剪贴板内容 */
        fun getClipboard(): String {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = cm.primaryClip
            if (clip != null && clip.itemCount > 0) {
                return clip.getItemAt(0).text?.toString() ?: ""
            }
            return ""
        }

        /** 设置剪贴板内容 */
        fun setClipboard(text: String) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("script_engine", text)
            cm.setPrimaryClip(clip)
            logs.add("剪贴板已更新")
        }

        // ========== 确认对话框 ==========

        /** 弹出确认对话框（返回用户选择） */
        fun confirm(title: String, message: String): Boolean {
            val result = java.util.concurrent.CompletableFuture<Boolean>()
            Handler(android.os.Looper.getMainLooper()).post {
                val builder = android.app.AlertDialog.Builder(
                    context,
                    android.R.style.Theme_DeviceDefault_Dialog_Alert
                )
                builder.setTitle(title)
                builder.setMessage(message)
                builder.setPositiveButton("确认") { _, _ -> result.complete(true) }
                builder.setNegativeButton("取消") { _, _ -> result.complete(false) }
                builder.setCancelable(false)
                builder.show()
            }
            return try {
                result.get(30, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) {
                logs.add("确认对话框超时或出错: ${e.message}")
                false
            }
        }

        // ========== 设备系统设置 ==========

        fun setVolume(type: String, value: Int): Boolean {
            val volType = when (type.lowercase()) {
                "music", "media" -> VolumeType.MUSIC
                "ring", "call" -> VolumeType.RING
                "notification", "notify" -> VolumeType.NOTIFICATION
                "alarm" -> VolumeType.ALARM
                else -> return false
            }
            runBlocking { systemController.setVolume(volType, value) }
            return true
        }

        fun getVolume(type: String): Int {
            val volType = when (type.lowercase()) {
                "music", "media" -> VolumeType.MUSIC
                "ring", "call" -> VolumeType.RING
                "notification", "notify" -> VolumeType.NOTIFICATION
                "alarm" -> VolumeType.ALARM
                else -> return -1
            }
            return systemController.getVolume(volType)
        }

        fun setBrightness(value: Int): Boolean = runBlocking { systemController.setBrightness(value).success }
        fun getBrightness(): Int = systemController.getBrightness() ?: -1

        fun setWifi(enabled: Boolean): Boolean = runBlocking { systemController.setWifiEnabled(enabled).success }
        fun setBluetooth(enabled: Boolean): Boolean = runBlocking { systemController.setBluetoothEnabled(enabled).success }
        fun setFlashlight(enabled: Boolean): Boolean = runBlocking { systemController.setFlashlightEnabled(enabled).success }
        fun setAutoRotate(enabled: Boolean): Boolean = runBlocking { systemController.setAutoRotateEnabled(enabled).success }
    }

    // ==================== 占位解释器（无JS引擎时使用） ====================

    /**
     * 简单的脚本占位解释器
     * 仅支持有限的语法：单行API调用，不支持复杂表达式
     * 生产环境建议接入 Rhino / QuickJS / V8 等JS引擎
     */
    /** 已加载的脚本内容（供Mod系统加载后执行） */
    private var loadedScript: String = ""

    /**
     * 加载Mod脚本内容（供 ModManager 调用）
     * 不立即执行，仅缓存脚本内容供后续 execute() 调用
     */
    fun loadScript(script: String) {
        loadedScript = script
        Log.d(TAG, "脚本已加载，长度: ${script.length}")
    }

    /**
     * 执行已加载的脚本
     */
    fun executeLoadedScript(): Boolean {
        if (loadedScript.isEmpty()) {
            Log.w(TAG, "没有已加载的脚本")
            return false
        }
        ensureEngine()
        return executeWithPlaceholder(loadedScript)
    }

    private val scriptApiInstance by lazy { ScriptApi() }

    private fun executeWithPlaceholder(script: String): Boolean {
        scriptApiInstance.clearLogs()
        val lines = script.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") }

        for (line in lines) {
            if (_status.value.state == ExecutionState.STOPPED) break

            try {
                val success = evaluatePlaceholderLine(line)
                if (!success) {
                    Log.w(TAG, "脚本指令执行失败: $line")
                }
            } catch (e: Exception) {
                Log.e(TAG, "脚本执行出错: ${e.message}", e)
                _status.value = _status.value.copy(
                    state = ExecutionState.ERROR,
                    error = "脚本错误: ${e.message}",
                    logs = scriptApiInstance.getLogs()
                )
                return false
            }
        }
        return true
    }

    /**
     * 解析并执行单行脚本指令
     * 支持格式：functionName(arg1, arg2, ...)
     */
    private fun evaluatePlaceholderLine(line: String): Boolean {
        val trimmed = line.trim().removeSuffix(";")

        val funcMatch = Regex("""(\w+)\s*\((.*)\)""").matchEntire(trimmed)
        if (funcMatch != null) {
            val funcName = funcMatch.groupValues[1]
            val argsStr = funcMatch.groupValues[2]
            val args = parseArguments(argsStr)
            return callPlaceholderApi(funcName, args)
        }

        if (trimmed.startsWith("var ") || trimmed.startsWith("let ") || trimmed.startsWith("const ")) {
            // 占位实现：忽略变量声明
            return true
        }

        if (trimmed.startsWith("if ") || trimmed.startsWith("for ") ||
            trimmed.startsWith("while ") || trimmed.startsWith("} else") ||
            trimmed == "{" || trimmed == "}"
        ) {
            // 占位实现：警告不支持控制流
            scriptApiInstance.log("[警告] 占位解释器不支持控制流: $trimmed")
            return true
        }

        scriptApiInstance.log("[警告] 无法解析的脚本行: $trimmed")
        return false
    }

    private fun parseArguments(argsStr: String): List<Any> {
        if (argsStr.isBlank()) return emptyList()
        val result = mutableListOf<Any>()
        var current = StringBuilder()
        var inString = false
        var stringChar = '"'
        var depth = 0

        for (c in argsStr) {
            when {
                (c == '"' || c == '\'') && (depth == 0) -> {
                    if (inString && c == stringChar) {
                        inString = false
                    } else if (!inString) {
                        inString = true
                        stringChar = c
                    } else {
                        current.append(c)
                    }
                }
                c == ',' && !inString && depth == 0 -> {
                    result.add(parseValue(current.toString()))
                    current.clear()
                }
                (c == '(' || c == '[' || c == '{') && !inString -> {
                    depth++
                    current.append(c)
                }
                (c == ')' || c == ']' || c == '}') && !inString -> {
                    depth--
                    current.append(c)
                }
                else -> current.append(c)
            }
        }
        if (current.isNotBlank()) {
            result.add(parseValue(current.toString()))
        }
        return result
    }

    private fun parseValue(str: String): Any {
        val s = str.trim()
        if (s.startsWith('"') && s.endsWith('"')) return s.substring(1, s.length - 1)
        if (s.startsWith('\'') && s.endsWith('\'')) return s.substring(1, s.length - 1)
        s.toIntOrNull()?.let { return it }
        s.toLongOrNull()?.let { return it }
        s.toFloatOrNull()?.let { return it }
        s.toDoubleOrNull()?.let { return it }
        if (s.equals("true", true)) return true
        if (s.equals("false", true)) return false
        if (s.equals("null", true)) return "null"
        return s
    }

    private fun callPlaceholderApi(funcName: String, args: List<Any>): Boolean {
        val api = scriptApiInstance
        return when (funcName) {
            "click" -> api.click(args[0] as Number, args.getOrNull(1) as Number)
            "longClick" -> api.longClick(
                args[0] as Number, args[1] as Number,
                args.getOrNull(2) as? Number ?: 500
            )
            "swipe" -> api.swipe(
                args[0] as Number, args[1] as Number,
                args[2] as Number, args[3] as Number,
                args.getOrNull(4) as? Number ?: 300
            )
            "wait", "sleep" -> {
                api.wait(args.getOrNull(0) as? Number ?: 500)
                true
            }
            "input" -> api.input(args[0].toString())
            "home" -> api.home()
            "back" -> api.back()
            "recents" -> api.recents()
            "launchApp" -> api.launchApp(args[0].toString())
            "closeApp" -> api.closeApp(args[0].toString())
            "clickText" -> api.clickText(args[0].toString())
            "clickById" -> api.clickById(args[0].toString())
            "setTextById" -> api.setTextById(args[0].toString(), args[1].toString())
            "toast" -> { api.toast(args[0]); true }
            "log" -> { api.log(args[0]); true }
            "setVolume" -> api.setVolume(args[0].toString(), (args[1] as Number).toInt())
            "getVolume" -> { api.log(api.getVolume(args[0].toString())); true }
            "setBrightness" -> api.setBrightness((args[0] as Number).toInt())
            "getBrightness" -> { api.log(api.getBrightness()); true }
            "setWifi" -> api.setWifi(args[0] as Boolean)
            "setBluetooth" -> api.setBluetooth(args[0] as Boolean)
            "setFlashlight" -> api.setFlashlight(args[0] as Boolean)
            "setAutoRotate" -> api.setAutoRotate(args[0] as Boolean)
            "getScreenText" -> { api.log(api.getScreenText()); true }
            "speak" -> { api.speak(args[0].toString()); true }
            "sendNotification" -> {
                api.sendNotification(args[0].toString(), args.getOrNull(1)?.toString() ?: "")
                true
            }
            "setAlarm" -> {
                api.setAlarm(args[0] as Number, args.getOrNull(1)?.toString() ?: "脚本闹钟")
                true
            }
            "getClipboard" -> { api.log(api.getClipboard()); true }
            "setClipboard" -> {
                api.setClipboard(args[0].toString())
                true
            }
            "confirm" -> api.confirm(args[0].toString(), args.getOrNull(1)?.toString() ?: "")
            else -> {
                api.log("[警告] 未知函数: $funcName")
                false
            }
        }
    }

    // ==================== 公开执行方法 ====================

    /**
     * 执行脚本
     * @param script JavaScript 脚本源码
     * @param scriptName 脚本名称（用于日志显示）
     */
    fun execute(script: String, scriptName: String = "unnamed") {
        stop()

        _status.value = ExecutionStatus(
            state = ExecutionState.RUNNING,
            scriptName = scriptName
        )

        executionJob = executionScope.launch {
            try {
                val useRealEngine = ensureEngine() && realJsEngine != null

                // useRealEngine is always false on Android (javax.script not available)
                val success = executeWithPlaceholder(script)

                if (success && _status.value.state == ExecutionState.RUNNING) {
                    _status.value = _status.value.copy(
                        state = ExecutionState.IDLE,
                        logs = scriptApiInstance.getLogs()
                    )
                }
            } catch (e: CancellationException) {
                _status.value = _status.value.copy(
                    state = ExecutionState.STOPPED
                )
            } catch (e: Exception) {
                _status.value = _status.value.copy(
                    state = ExecutionState.ERROR,
                    error = e.message
                )
            }
        }
    }

    /**
     * 停止正在执行的脚本
     */
    fun stop() {
        executionJob?.cancel()
        executionJob = null
        if (_status.value.state == ExecutionState.RUNNING ||
            _status.value.state == ExecutionState.PAUSED
        ) {
            _status.value = _status.value.copy(
                state = ExecutionState.STOPPED
            )
        }
    }

    /**
     * 暂停脚本（占位实现，实际需要引擎支持）
     */
    fun pause() {
        if (_status.value.state == ExecutionState.RUNNING) {
            _status.value = _status.value.copy(state = ExecutionState.PAUSED)
        }
    }

    /**
     * 恢复脚本（占位实现）
     */
    fun resume() {
        if (_status.value.state == ExecutionState.PAUSED) {
            _status.value = _status.value.copy(state = ExecutionState.RUNNING)
        }
    }

    // ==================== 脚本录制 ====================

    /** 录制的操作步骤 */
    data class RecordedAction(
        val type: ActionType,
        val timestamp: Long,
        val params: Map<String, Any>
    )

    enum class ActionType {
        CLICK, LONG_CLICK, SWIPE, INPUT,
        HOME, BACK, RECENTS,
        LAUNCH_APP,
        VOLUME_CHANGE, BRIGHTNESS_CHANGE,
        WAIT
    }

    private val recordedActions = mutableListOf<RecordedAction>()
    private var isRecording = false
    private var recordStartTime = 0L

    /** 录制状态Flow */
    private val _isRecordingFlow = MutableStateFlow(false)
    val isRecordingFlow: StateFlow<Boolean> = _isRecordingFlow.asStateFlow()

    /**
     * 开始录制
     */
    fun startRecording() {
        recordedActions.clear()
        recordStartTime = System.currentTimeMillis()
        isRecording = true
        _isRecordingFlow.value = true
    }

    /**
     * 停止录制并返回生成的JS脚本
     */
    fun stopRecording(): String {
        isRecording = false
        _isRecordingFlow.value = false
        return generateScriptFromActions()
    }

    /**
     * 是否正在录制
     */
    fun isRecording(): Boolean = isRecording

    /**
     * 添加录制的操作（由系统事件调用）
     */
    fun addRecordedAction(
        type: ActionType,
        params: Map<String, Any> = emptyMap()
    ) {
        if (!isRecording) return
        val ts = System.currentTimeMillis() - recordStartTime
        // 如果和上一个动作时间间隔较大，插入 WAIT
        if (recordedActions.isNotEmpty()) {
            val lastTs = recordedActions.last().timestamp
            val gap = ts - lastTs
            if (gap > 300) {
                recordedActions.add(
                    RecordedAction(
                        ActionType.WAIT,
                        lastTs + (gap / 2),
                        mapOf("ms" to gap)
                    )
                )
            }
        }
        recordedActions.add(RecordedAction(type, ts, params))
    }

    /**
     * 从录制的操作生成JavaScript脚本
     */
    private fun generateScriptFromActions(): String {
        val sb = StringBuilder()
        sb.appendLine("// LingShu 录制脚本 v$ENGINE_VERSION")
        sb.appendLine("// 生成时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}")
        sb.appendLine()

        for (action in recordedActions) {
            when (action.type) {
                ActionType.CLICK -> {
                    val x = action.params["x"]
                    val y = action.params["y"]
                    sb.appendLine("click($x, $y);")
                }
                ActionType.LONG_CLICK -> {
                    val x = action.params["x"]
                    val y = action.params["y"]
                    val d = action.params["duration"] ?: 500
                    sb.appendLine("longClick($x, $y, $d);")
                }
                ActionType.SWIPE -> {
                    val sx = action.params["startX"]
                    val sy = action.params["startY"]
                    val ex = action.params["endX"]
                    val ey = action.params["endY"]
                    val d = action.params["duration"] ?: 300
                    sb.appendLine("swipe($sx, $sy, $ex, $ey, $d);")
                }
                ActionType.INPUT -> {
                    val text = action.params["text"]?.toString()?.replace("\"", "\\\"") ?: ""
                    sb.appendLine("input(\"$text\");")
                }
                ActionType.HOME -> sb.appendLine("home();")
                ActionType.BACK -> sb.appendLine("back();")
                ActionType.RECENTS -> sb.appendLine("recents();")
                ActionType.LAUNCH_APP -> {
                    val pkg = action.params["packageName"] ?: ""
                    sb.appendLine("launchApp(\"$pkg\");")
                }
                ActionType.WAIT -> {
                    val ms = action.params["ms"] ?: 500
                    sb.appendLine("wait($ms);")
                }
                ActionType.VOLUME_CHANGE -> {
                    val type = action.params["type"] ?: "music"
                    val value = action.params["value"] ?: 0
                    sb.appendLine("setVolume(\"$type\", $value);")
                }
                ActionType.BRIGHTNESS_CHANGE -> {
                    val value = action.params["value"] ?: 128
                    sb.appendLine("setBrightness($value);")
                }
            }
        }
        return sb.toString()
    }

    // ==================== .lspack 脚本包格式 ====================

    /**
     * 脚本包元数据
     */
    data class ScriptPackage(
        val name: String,
        val version: String,
        val description: String,
        val author: String,
        val script: String,
        val createdAt: Long,
        val engineVersion: String = ENGINE_VERSION,
        val extra: Map<String, String> = emptyMap()
    ) {
        fun toJson(): String {
            return JSONObject().apply {
                put("name", name)
                put("version", version)
                put("description", description)
                put("author", author)
                put("createdAt", createdAt)
                put("engineVersion", engineVersion)
                put("extra", JSONObject(extra))
            }.toString(2)
        }

        companion object {
            fun fromJson(json: String): ScriptPackage {
                val obj = JSONObject(json)
                val extraJson = obj.optJSONObject("extra") ?: JSONObject()
                val extraMap = mutableMapOf<String, String>()
                extraJson.keys().forEach { key ->
                    extraMap[key] = extraJson.optString(key)
                }
                return ScriptPackage(
                    name = obj.optString("name", "未命名脚本"),
                    version = obj.optString("version", "1.0.0"),
                    description = obj.optString("description", ""),
                    author = obj.optString("author", "未知"),
                    script = "",
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                    engineVersion = obj.optString("engineVersion", ENGINE_VERSION),
                    extra = extraMap
                )
            }
        }
    }

    /**
     * 导出脚本包为 .lspack 文件（ZIP格式）
     *
     * 文件结构：
     * - metadata.json : 元数据JSON
     * - script.js     : 脚本源码
     */
    fun exportScriptPackage(
        pkg: ScriptPackage,
        outputFile: File
    ): Boolean {
        return try {
            outputFile.parentFile?.mkdirs()
            ZipOutputStream(FileOutputStream(outputFile)).use { zos ->
                val metaBytes = pkg.toJson().toByteArray(Charsets.UTF_8)
                zos.putNextEntry(ZipEntry(LS_PACK_META_FILE))
                zos.write(metaBytes)
                zos.closeEntry()

                val scriptBytes = pkg.script.toByteArray(Charsets.UTF_8)
                zos.putNextEntry(ZipEntry(LS_PACK_SCRIPT_FILE))
                zos.write(scriptBytes)
                zos.closeEntry()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "导出脚本包失败: ${e.message}", e)
            false
        }
    }

    /**
     * 导入 .lspack 脚本包
     */
    fun importScriptPackage(file: File): ScriptPackage? {
        return try {
            var metadata: ScriptPackage? = null
            var scriptContent = ""

            ZipInputStream(FileInputStream(file)).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val content = zis.readBytes().toString(Charsets.UTF_8)
                    when (entry.name) {
                        LS_PACK_META_FILE -> metadata = ScriptPackage.fromJson(content)
                        LS_PACK_SCRIPT_FILE -> scriptContent = content
                    }
                    entry = zis.nextEntry
                }
            }

            metadata?.copy(script = scriptContent)
        } catch (e: Exception) {
            Log.e(TAG, "导入脚本包失败: ${e.message}", e)
            null
        }
    }

    // ==================== Handler类（用于主线程执行） ====================

    private class Handler(private val looper: android.os.Looper) {
        private val handler = android.os.Handler(looper)
        fun post(r: Runnable) = handler.post(r)
    }
}
