package com.lingshu.agent.feature.floating.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lingshu.agent.R
import com.lingshu.agent.feature.floating.FloatingBubbleManager
import com.lingshu.agent.feature.floating.FloatingBubbleState
import com.lingshu.agent.feature.model.MessageRole
import com.lingshu.agent.feature.model.ModelMessage
import com.lingshu.agent.feature.model.ModelRouter
import com.lingshu.agent.feature.voice.TextToSpeechManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 悬浮窗前台服务
 *
 * 核心功能：
 * 1. 前台服务常驻（FOREGROUND_SERVICE_TYPE_SPECIAL_USE），保证悬浮窗不被系统回收
 * 2. 管理 FloatingBubbleManager 的生命周期：onCreate时启动气泡，onDestroy时销毁
 * 3. 作为「中央消息分发器」：接收来自 VoiceSession / ModelRouter / 其他模块的广播更新气泡状态
 * 4. 处理 FloatingBubbleManager 的回调：快捷对话发送 -> ModelRouter.chat -> 状态切换 -> 结果
 *
 * 广播 Action 列表：
 * - ACTION_UPDATE_STATE: 外部组件请求更新气泡状态（extra: EXTRA_STATE）
 * - ACTION_SHOW_BUBBLE: 显示气泡
 * - ACTION_HIDE_BUBBLE: 隐藏气泡
 *
 * Android 适配：
 * - Android O+: 创建 NotificationChannel
 * - Android 14 (API 34+): 使用 FOREGROUND_SERVICE_TYPE_SPECIAL_USE（悬浮窗/系统overlay专用类型）
 */
@AndroidEntryPoint
class FloatingBubbleService : Service() {

    // ==================== Binder ====================
    inner class LocalBinder : Binder() {
        fun getService(): FloatingBubbleService = this@FloatingBubbleService
    }
    private val binder = LocalBinder()

    // ==================== 注入 ====================
    @Inject lateinit var bubbleManager: FloatingBubbleManager
    @Inject lateinit var modelRouter: ModelRouter
    @Inject lateinit var ttsManager: TextToSpeechManager

    // ==================== 协程作用域 ====================
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ==================== 状态 ====================
    private var isRunning = false

    // ==================== 广播接收器 ====================
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_UPDATE_STATE -> {
                    val stateName = intent.getStringExtra(EXTRA_STATE)
                    val state = FloatingBubbleState.safeValueOf(stateName)
                    bubbleManager.setState(state)
                }
                ACTION_SHOW_BUBBLE -> bubbleManager.show()
                ACTION_HIDE_BUBBLE -> bubbleManager.hide()
                ACTION_SHOW_CHAT -> bubbleManager.showChatPanel()
                ACTION_HIDE_CHAT -> bubbleManager.hideChatPanel()
            }
        }
    }

    companion object {
        private const val TAG = "FloatingBubbleService"

        const val NOTIFICATION_ID = 10001
        const val CHANNEL_ID = "floating_bubble_channel"

        const val ACTION_UPDATE_STATE = "com.lingshu.agent.floating.UPDATE_STATE"
        const val ACTION_SHOW_BUBBLE = "com.lingshu.agent.floating.SHOW_BUBBLE"
        const val ACTION_HIDE_BUBBLE = "com.lingshu.agent.floating.HIDE_BUBBLE"
        const val ACTION_SHOW_CHAT = "com.lingshu.agent.floating.SHOW_CHAT"
        const val ACTION_HIDE_CHAT = "com.lingshu.agent.floating.HIDE_CHAT"

        const val EXTRA_STATE = "extra_bubble_state"

        fun createStartIntent(context: Context): Intent {
            return Intent(context, FloatingBubbleService::class.java)
        }

        fun createStopIntent(context: Context): Intent {
            return Intent(context, FloatingBubbleService::class.java)
        }

        /**
         * 发送更新气泡状态的广播（无需绑定Service）
         */
        fun sendUpdateState(context: Context, state: FloatingBubbleState) {
            val intent = Intent(ACTION_UPDATE_STATE).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_STATE, state.name)
            }
            context.sendBroadcast(intent)
        }

        fun sendShowBubble(context: Context) {
            context.sendBroadcast(Intent(ACTION_SHOW_BUBBLE).apply {
                setPackage(context.packageName)
            })
        }

        fun sendHideBubble(context: Context) {
            context.sendBroadcast(Intent(ACTION_HIDE_BUBBLE).apply {
                setPackage(context.packageName)
            })
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        createNotificationChannel()
        startForegroundWithNotification()
        setupBubbleCallbacks()
        registerBroadcastReceiver()
        bubbleManager.show()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand startId=$startId")
        if (!isRunning) {
            bubbleManager.show()
            isRunning = true
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy")
        unregisterBroadcastReceiver()
        bubbleManager.dismiss()
        serviceScope.cancel()
        isRunning = false
    }

    // ==================== 通知 & 前台服务 ====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "灵枢悬浮助手",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "悬浮窗服务常驻通知，可隐藏不影响使用"
                setShowBadge(false)
                enableVibration(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithNotification() {
        val pendingIntent: PendingIntent? = packageManager.getLaunchIntentForPackage(packageName)?.let {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            PendingIntent.getActivity(this, 0, it, flags)
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("灵枢助手")
            .setContentText("悬浮窗已启用，随时待命")
            .setSmallIcon(R.drawable.ic_mic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ==================== 广播注册 ====================

    private fun registerBroadcastReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_UPDATE_STATE)
            addAction(ACTION_SHOW_BUBBLE)
            addAction(ACTION_HIDE_BUBBLE)
            addAction(ACTION_SHOW_CHAT)
            addAction(ACTION_HIDE_CHAT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateReceiver, filter)
        }
    }

    private fun unregisterBroadcastReceiver() {
        runCatching { unregisterReceiver(stateReceiver) }
    }

    // ==================== 气泡回调：快捷对话 -> ModelRouter ====================

    private fun setupBubbleCallbacks() {
        bubbleManager.onChatSend = { message ->
            handleChatSend(message)
        }
        bubbleManager.onQuickCommand = { command ->
            handleChatSend(command)
        }
        bubbleManager.onBubbleClick = {
            if (bubbleManager.isChatVisible.value) {
                bubbleManager.hideChatPanel()
            } else {
                bubbleManager.setState(FloatingBubbleState.AWAKENED)
                bubbleManager.showChatPanel()
            }
        }
    }

    /**
     * 处理快捷对话发送的消息
     * 工作流：输入 -> THINKING状态 -> ModelRouter -> 显示结果 -> 回到待机
     */
    private fun handleChatSend(message: String) {
        serviceScope.launch(Dispatchers.Main) {
            bubbleManager.setState(FloatingBubbleState.THINKING)
            val result = runCatching {
                val messages = listOf(
                    ModelMessage(MessageRole.SYSTEM, "你是灵枢个人AI助手。回答请简洁，最多3句话。"),
                    ModelMessage(MessageRole.USER, message)
                )
                modelRouter.chat(messages)
            }
            result.onSuccess { resp ->
                bubbleManager.setState(FloatingBubbleState.EXECUTING)
                val text = resp.safeContent.takeIf { it.isNotBlank() } ?: "（已收到回复）"
                ttsManager.speak(text)
                serviceScope.launch(Dispatchers.Main) {
                    kotlinx.coroutines.delay(2000)
                    bubbleManager.setState(FloatingBubbleState.STANDBY)
                }
            }
            result.onFailure {
                bubbleManager.setState(FloatingBubbleState.STANDBY)
                ttsManager.speak("抱歉，处理请求时出错")
            }
        }
    }

    /**
     * 外部主动控制API（供绑定模式使用）
     */
    fun updateBubbleState(state: FloatingBubbleState) {
        bubbleManager.setState(state)
    }

    fun showBubble() = bubbleManager.show()
    fun hideBubble() = bubbleManager.hide()
    fun isServiceRunning(): Boolean = isRunning
}
