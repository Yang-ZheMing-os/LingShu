package com.lingshu.agent.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.lingshu.agent.R
import com.lingshu.agent.feature.control.AccessibilityController
import com.lingshu.agent.feature.control.SystemController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 语音助理前台服务（Voice Assistant Foreground Service）
 *
 * 功能：
 * 1. 常驻前台服务，避免被系统杀死
 * 2. 作为语音交互的中枢，串联：
 *    - 语音输入识别（SpeechRecognizer）
 *    - 语音指令解析（接入LLM）
 *    - 指令映射到控制模块（SystemController / AccessibilityController）
 *    - 结果反馈 TTS 播报
 * 3. 提供绑定接口，供UI层绑定调用
 *
 * 注意：
 * - 与现有 WakeWordService 分工不同：
 *   WakeWordService 负责低功耗唤醒词检测
 *   VoiceAssistantService 负责完整的语音交互流程
 * - 实际语音识别/合成使用 feature/voice/ 下现成组件
 */
@AndroidEntryPoint
class VoiceAssistantService : Service() {

    companion object {
        private const val TAG = "VoiceAssistantService"

        /** 通知渠道ID */
        const val NOTIFICATION_CHANNEL_ID = "lingshu_voice_assistant_channel"

        /** 通知渠道名称 */
        const val NOTIFICATION_CHANNEL_NAME = "灵枢语音助理"

        /** 前台服务通知ID */
        const val FOREGROUND_NOTIFICATION_ID = 2001

        /** Action: 启动服务 */
        const val ACTION_START = "com.lingshu.agent.START_VOICE_ASSISTANT"

        /** Action: 停止服务 */
        const val ACTION_STOP = "com.lingshu.agent.STOP_VOICE_ASSISTANT"

        /** Action: 开始一次语音交互（从唤醒词检测后调用） */
        const val ACTION_START_INTERACTION = "com.lingshu.agent.START_INTERACTION"

        /** Action: 停止当前交互 */
        const val ACTION_STOP_INTERACTION = "com.lingshu.agent.STOP_INTERACTION"

        /** 广播Action: 交互状态变化 */
        const val BROADCAST_INTERACTION_STATE = "com.lingshu.agent.VOICE_INTERACTION_STATE"

        /** Extra: 状态值 */
        const val EXTRA_STATE = "extra_state"

        /** Extra: 识别文本 */
        const val EXTRA_RECOGNIZED_TEXT = "extra_recognized_text"

        /** Extra: 回复文本 */
        const val EXTRA_REPLY_TEXT = "extra_reply_text"

        /** 交互状态 */
        enum class InteractionState {
            IDLE,           // 空闲
            LISTENING,      // 聆听中
            RECOGNIZING,    // 识别中
            THINKING,       // 思考中（调用LLM）
            SPEAKING,       // 回复播报中
            ERROR           // 出错
        }
    }

    // ==================== 注入 ====================

    @Inject
    lateinit var systemController: SystemController

    @Inject
    lateinit var accessibilityController: AccessibilityController

    // ==================== Binder ====================

    inner class LocalBinder : Binder() {
        fun getService(): VoiceAssistantService = this@VoiceAssistantService
    }

    private val binder = LocalBinder()

    // ==================== 状态 ====================

    /** 通知管理器 */
    private lateinit var notificationManager: NotificationManager

    /** 服务是否已启动 */
    private var isServiceRunning = false

    /** 当前交互状态 */
    private var currentState: InteractionState = InteractionState.IDLE

    // ==================== 生命周期 ====================

    override fun onCreate() {
        super.onCreate()

        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForegroundServiceInternal()
            ACTION_STOP -> stopServiceInternal()
            ACTION_START_INTERACTION -> startInteraction()
            ACTION_STOP_INTERACTION -> stopInteraction()
            null -> startForegroundServiceInternal()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        return true
    }

    override fun onDestroy() {
        try {
            stopInteraction()
        } catch (_: Exception) {}
        isServiceRunning = false
        super.onDestroy()
    }

    // ==================== 前台服务管理 ====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    NOTIFICATION_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "灵枢语音助理运行中"
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                }
                notificationManager.createNotificationChannel(channel)
            }
        }
    }

    private fun startForegroundServiceInternal() {
        if (isServiceRunning) {
            updateNotification(InteractionState.IDLE, null)
            return
        }

        val notification = buildNotification(InteractionState.IDLE, null)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
        }

        isServiceRunning = true
    }

    private fun stopServiceInternal() {
        stopInteraction()
        isServiceRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ==================== 交互流程 ====================

    /**
     * 开始一次语音交互流程
     * 1. 启动语音识别
     * 2. 识别用户输入
     * 3. 解析指令（调用LLM，此处为占位）
     * 4. 执行对应操作（SystemController / AccessibilityController）
     * 5. TTS 播报结果
     */
    private fun startInteraction() {
        if (currentState != InteractionState.IDLE &&
            currentState != InteractionState.ERROR
        ) {
            return
        }

        updateState(InteractionState.LISTENING)

        // TODO: 接入实际的语音识别流程
        // 此处为占位：延迟模拟识别结果
        android.os.Handler(mainLooper).postDelayed({
            // 模拟识别到的文本
            val mockRecognizedText = "打开微信"
            handleRecognizedText(mockRecognizedText)
        }, 1500L)
    }

    /**
     * 处理识别到的用户文本
     */
    private fun handleRecognizedText(text: String) {
        updateState(InteractionState.THINKING, text)

        // TODO: 接入实际LLM解析，将自然语言映射为控制指令
        // 此处为简单示例：关键词匹配
        android.os.Handler(mainLooper).postDelayed({
            val reply = processCommand(text)
            speakReply(reply, text)
        }, 500L)
    }

    /**
     * 简单的命令处理（占位，实际应由LLM生成结构化指令）
     */
    private fun processCommand(text: String): String {
        return when {
            text.contains("打开微信", ignoreCase = true) -> {
                val result = kotlinx.coroutines.runBlocking { systemController.launchApp("com.tencent.mm") }
                if (result.success) "好的，正在打开微信" else "抱歉，未找到微信应用"
            }
            text.contains("手电筒") && text.contains("开", ignoreCase = true) -> {
                val result = kotlinx.coroutines.runBlocking { systemController.setFlashlightEnabled(true) }
                if (result.success) "已打开手电筒" else "打开手电筒失败"
            }
            text.contains("手电筒") && text.contains("关", ignoreCase = true) -> {
                val result = kotlinx.coroutines.runBlocking { systemController.setFlashlightEnabled(false) }
                if (result.success) "已关闭手电筒" else "关闭手电筒失败"
            }
            text.contains("返回", ignoreCase = true) -> {
                val ok = accessibilityController.pressBack()
                if (ok) "好的" else "操作失败，请确认无障碍服务已开启"
            }
            text.contains("主页") || text.contains("主屏幕") -> {
                val ok = accessibilityController.pressHome()
                if (ok) "好的，返回主页" else "操作失败"
            }
            else -> "您说的是：$text，此功能开发中"
        }
    }

    /**
     * 播报回复
     */
    private fun speakReply(reply: String, recognizedText: String?) {
        updateState(InteractionState.SPEAKING, recognizedText, reply)

        // TODO: 接入实际TTS播报
        // 此处为占位：延迟模拟播报完成
        android.os.Handler(mainLooper).postDelayed({
            updateState(InteractionState.IDLE, recognizedText, reply)
        }, 2000L)
    }

    /**
     * 停止当前交互流程
     */
    private fun stopInteraction() {
        updateState(InteractionState.IDLE)
    }

    // ==================== 状态广播 & 通知 ====================

    private fun updateState(
        newState: InteractionState,
        recognizedText: String? = null,
        replyText: String? = null
    ) {
        currentState = newState

        // 发送广播
        val broadcast = Intent(BROADCAST_INTERACTION_STATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATE, newState.name)
            recognizedText?.let { putExtra(EXTRA_RECOGNIZED_TEXT, it) }
            replyText?.let { putExtra(EXTRA_REPLY_TEXT, it) }
        }
        sendBroadcast(broadcast)

        // 更新通知
        updateNotification(newState, recognizedText)
    }

    private fun buildNotification(
        state: InteractionState,
        recognizedText: String?
    ): Notification {
        val (title, content) = when (state) {
            InteractionState.IDLE ->
                getString(R.string.va_notification_title_idle) to
                        getString(R.string.va_notification_content_idle)
            InteractionState.LISTENING ->
                getString(R.string.va_notification_title_listening) to
                        getString(R.string.va_notification_content_listening)
            InteractionState.RECOGNIZING ->
                getString(R.string.va_notification_title_recognizing) to
                        (recognizedText ?: getString(R.string.va_notification_content_recognizing))
            InteractionState.THINKING ->
                getString(R.string.va_notification_title_thinking) to
                        getString(R.string.va_notification_content_thinking)
            InteractionState.SPEAKING ->
                getString(R.string.va_notification_title_speaking) to
                        getString(R.string.va_notification_content_speaking)
            InteractionState.ERROR ->
                getString(R.string.va_notification_title_error) to
                        getString(R.string.va_notification_content_error)
        }

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_voice_notification)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        // 点击通知打开主应用
        packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
            val pending = PendingIntent.getActivity(
                this, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setContentIntent(pending)
        }

        // 停止服务按钮
        val stopIntent = Intent(this, VoiceAssistantService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(
            R.drawable.ic_stop_notification,
            getString(R.string.va_action_stop_service),
            stopPending
        )

        return builder.build()
    }

    private fun updateNotification(state: InteractionState, recognizedText: String?) {
        val notification = buildNotification(state, recognizedText)
        notificationManager.notify(FOREGROUND_NOTIFICATION_ID, notification)
    }

    // ==================== 公开API ====================

    /** 当前状态 */
    fun getCurrentState(): InteractionState = currentState

    /** 服务是否正在运行 */
    fun isRunning(): Boolean = isServiceRunning

    /**
     * 通过API触发一次语音交互
     */
    fun triggerInteraction() {
        startInteraction()
    }

    /**
     * 通过API直接发送文本指令（绕过语音识别，供调试或其他模块调用）
     */
    fun sendTextCommand(text: String) {
        updateState(InteractionState.RECOGNIZING, text)
        handleRecognizedText(text)
    }
}
