package com.lingshu.agent.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.lingshu.agent.R
import com.lingshu.agent.feature.voice.VoiceSession
import com.lingshu.agent.feature.voice.WakeWordDetector
import com.lingshu.agent.feature.voice.NoOpWakeWordDetector

/**
 * 唤醒词前台服务（Foreground Service）
 *
 * 功能：
 * 1. 低功耗后台持续监听唤醒词（"灵枢"）
 * 2. 常驻通知栏，避免被系统杀死（Android 8.0+ 必须使用前台服务）
 * 3. 与 VoiceSession 联动：检测到唤醒词后启动会话
 * 4. 支持绑定模式，供 Activity/Fragment 直接控制
 * 5. 通过广播将会话状态同步到UI层
 *
 * 使用方式：
 * - startService(Intent) + startForeground 启动后台监听
 * - bindService 绑定获取 VoiceSession 引用进行交互
 *
 * Android 版本适配：
 * - Android O (API 26) 及以上：必须创建 NotificationChannel
 * - Android Q (API 29) 及以上：麦克风前台服务需指定 FOREGROUND_SERVICE_TYPE_MICROPHONE
 * - Android 12 (API 31) 及以上：PendingIntent 需显式指定可变性 FLAG_IMMUTABLE/FLAG_MUTABLE
 */
class WakeWordService : Service() {

    /**
     * 本地Binder，用于Activity绑定后获取Service实例
     */
    inner class LocalBinder : Binder() {
        fun getService(): WakeWordService = this@WakeWordService
    }

    private val binder = LocalBinder()

    /** VoiceSession 实例 - 整个应用复用一个会话 */
    lateinit var voiceSession: VoiceSession
        private set

    /** 唤醒词检测器实例 */
    private lateinit var wakeWordDetector: WakeWordDetector

    /** 通知管理器 */
    private lateinit var notificationManager: NotificationManager

    /** 服务是否已启动 */
    private var isServiceRunning: Boolean = false

    /** 当前是否处于激活会话中 */
    private var isSessionActive: Boolean = false

    companion object {
        /** 通知渠道ID */
        const val NOTIFICATION_CHANNEL_ID = "lingshu_wakeword_channel"

        /** 通知渠道名称 */
        const val NOTIFICATION_CHANNEL_NAME = "灵枢唤醒词监听"

        /** 前台服务通知ID */
        const val FOREGROUND_NOTIFICATION_ID = 1001

        /** Action: 启动服务 */
        const val ACTION_START_SERVICE = "com.lingshu.agent.START_WAKEWORD_SERVICE"

        /** Action: 停止服务 */
        const val ACTION_STOP_SERVICE = "com.lingshu.agent.STOP_WAKEWORD_SERVICE"

        /** Action: 手动触发唤醒（模拟唤醒词） */
        const val ACTION_TRIGGER_WAKE = "com.lingshu.agent.TRIGGER_WAKE"

        /** Action: 手动结束当前会话 */
        const val ACTION_END_SESSION = "com.lingshu.agent.END_SESSION"

        /** 广播Action: 检测到唤醒词 */
        const val BROADCAST_WAKEWORD_DETECTED = "com.lingshu.agent.WAKEWORD_DETECTED"

        /** 广播Action: 会话状态变化 */
        const val BROADCAST_SESSION_STATE_CHANGED = "com.lingshu.agent.SESSION_STATE_CHANGED"

        /** 广播Extra: 唤醒词文本 */
        const val EXTRA_WAKE_WORD = "extra_wake_word"

        /** 广播Extra: 置信度 */
        const val EXTRA_CONFIDENCE = "extra_confidence"

        /** 广播Extra: 会话状态 */
        const val EXTRA_SESSION_STATE = "extra_session_state"
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // 创建通知渠道（Android O及以上必须）
        createNotificationChannel()

        // 初始化唤醒词检测器（使用实际引擎替换NoOp实现）
        wakeWordDetector = NoOpWakeWordDetector().apply {
            init(this@WakeWordService)
        }

        // 初始化 VoiceSession，注入唤醒词检测器
        voiceSession = VoiceSession(
            context = this,
            wakeWordDetector = wakeWordDetector
        )

        // 注册 VoiceSession 回调以同步状态到广播
        setupSessionCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVICE -> {
                startWakeWordForeground()
            }
            ACTION_STOP_SERVICE -> {
                stopWakeWordService()
                return START_NOT_STICKY
            }
            ACTION_TRIGGER_WAKE -> {
                // 模拟唤醒（如通知栏快捷方式点击）
                if (!isSessionActive) {
                    voiceSession.startSession()
                }
            }
            ACTION_END_SESSION -> {
                // 通知栏"结束会话"按钮点击
                voiceSession.terminateSession()
            }
            null -> {
                // 默认启动前台服务
                startWakeWordForeground()
            }
        }
        // START_STICKY：服务被系统杀死后自动重建
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // 返回true表示onRebind会被调用（下次绑定时）
        return true
    }

    override fun onDestroy() {
        try {
            voiceSession.release()
            wakeWordDetector.release()
        } catch (_: Exception) {
            // 忽略销毁时的异常
        }
        isServiceRunning = false
        super.onDestroy()
    }

    /**
     * 启动前台服务并开始监听唤醒词
     * 这是后台唤醒词监听的入口
     */
    private fun startWakeWordForeground() {
        if (isServiceRunning) {
            // 已在运行，仅确保在前台
            val notification = buildNotification(
                getString(R.string.wakeword_notification_title_idle),
                getString(R.string.wakeword_notification_content_idle)
            )
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
            return
        }

        // 构建初始前台通知
        val notification = buildNotification(
            getString(R.string.wakeword_notification_title_idle),
            getString(R.string.wakeword_notification_content_idle)
        )

        // Android Q及以上需要指定前台服务类型为麦克风（隐私合规要求）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
        }

        // 初始化 VoiceSession（如未初始化）
        if (!voiceSession.getState().let {
                it == VoiceSession.SessionState.IDLE ||
                        it == VoiceSession.SessionState.ENDED
            }
        ) {
            voiceSession.initialize()
        }

        // 启动唤醒词监听（后台持续监听模式）
        val success = voiceSession.startWakeWordListening()
        if (success) {
            isServiceRunning = true
            updateNotification(
                getString(R.string.wakeword_notification_title_idle),
                getString(R.string.wakeword_notification_content_idle)
            )
        } else {
            // 监听失败（可能是权限问题）
            updateNotification(
                getString(R.string.wakeword_notification_title_error),
                getString(R.string.wakeword_notification_content_error)
            )
        }
    }

    /**
     * 停止唤醒词服务
     * 释放所有资源，退出前台，停止服务
     */
    private fun stopWakeWordService() {
        voiceSession.stopWakeWordListening()
        voiceSession.terminateSession()
        isServiceRunning = false
        // STOP_FOREGROUND_REMOVE：同时移除通知
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * 设置 VoiceSession 回调，用于同步状态到广播和通知栏
     * 通过广播让UI层（Activity/Fragment）感知会话状态变化
     */
    private fun setupSessionCallback() {
        voiceSession.setCallback(object : VoiceSession.SessionCallback {
            override fun onStateChanged(
                newState: VoiceSession.SessionState,
                oldState: VoiceSession.SessionState
            ) {
                // 发送广播通知UI层状态变更
                sendBroadcast(Intent(BROADCAST_SESSION_STATE_CHANGED).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_SESSION_STATE, newState.name)
                })

                // 根据状态更新通知栏
                when (newState) {
                    VoiceSession.SessionState.IDLE -> {
                        isSessionActive = false
                        updateNotification(
                            getString(R.string.wakeword_notification_title_idle),
                            getString(R.string.wakeword_notification_content_idle)
                        )
                    }
                    VoiceSession.SessionState.LISTENING,
                    VoiceSession.SessionState.RECOGNIZING -> {
                        isSessionActive = true
                        updateNotification(
                            getString(R.string.wakeword_notification_title_listening),
                            getString(R.string.wakeword_notification_content_listening),
                            showEndSessionAction = true
                        )
                    }
                    VoiceSession.SessionState.THINKING -> {
                        updateNotification(
                            getString(R.string.wakeword_notification_title_thinking),
                            getString(R.string.wakeword_notification_content_thinking),
                            showEndSessionAction = true
                        )
                    }
                    VoiceSession.SessionState.SPEAKING -> {
                        updateNotification(
                            getString(R.string.wakeword_notification_title_speaking),
                            getString(R.string.wakeword_notification_content_speaking),
                            showEndSessionAction = true
                        )
                    }
                    VoiceSession.SessionState.ENDED -> {
                        isSessionActive = false
                        // 会话结束后，自动恢复唤醒词监听（后台等待下一次唤醒）
                        voiceSession.startWakeWordListening()
                        updateNotification(
                            getString(R.string.wakeword_notification_title_idle),
                            getString(R.string.wakeword_notification_content_idle)
                        )
                    }
                    VoiceSession.SessionState.ERROR -> {
                        updateNotification(
                            getString(R.string.wakeword_notification_title_error),
                            getString(R.string.wakeword_notification_content_error)
                        )
                    }
                }
            }

            override fun onWakeWord(wakeWord: String, confidence: Float) {
                // 发送唤醒词广播，UI层可播放唤醒提示音/动画
                sendBroadcast(Intent(BROADCAST_WAKEWORD_DETECTED).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_WAKE_WORD, wakeWord)
                    putExtra(EXTRA_CONFIDENCE, confidence)
                })
            }

            override fun onUserSpeechStart() { /* UI层自行通过状态广播处理 */ }
            override fun onUserSpeechEnd() { /* UI层自行通过状态广播处理 */ }
            override fun onUserText(text: String, isFinal: Boolean) { /* UI层自行处理 */ }
            override fun onUserInputComplete(text: String) { /* UI层自行处理 */ }
            override fun onReplyStart(text: String) { /* UI层自行处理 */ }
            override fun onReplyDone(text: String) { /* UI层自行处理 */ }
            override fun onUserVolumeChanged(volume: Float) { /* UI层自行处理 */ }
            override fun onSessionTimeout() { /* UI层自行处理 */ }
            override fun onSessionTerminated() { /* UI层自行处理 */ }
            override fun onBargeIn() { /* UI层自行处理 */ }

            override fun onError(errorCode: Int, errorMessage: String) {
                // 错误状态更新到通知栏
                updateNotification(
                    getString(R.string.wakeword_notification_title_error),
                    errorMessage
                )
            }
        })
    }

    /**
     * 创建通知渠道（Android 8.0+）
     * 前台服务必须通过 NotificationManager 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existingChannel = notificationManager.getNotificationChannel(
                NOTIFICATION_CHANNEL_ID
            )
            if (existingChannel == null) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    NOTIFICATION_CHANNEL_NAME,
                    // IMPORTANCE_LOW：避免发出通知提示音，只在通知栏显示
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.wakeword_notification_channel_desc)
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                }
                notificationManager.createNotificationChannel(channel)
            }
        }
    }

    /**
     * 构建前台通知
     * @param title 通知标题
     * @param content 通知内容
     * @param showEndSessionAction 是否显示"结束会话"动作按钮
     */
    private fun buildNotification(
        title: String,
        content: String,
        showEndSessionAction: Boolean = false
    ): Notification {
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            // TODO: 实际项目中替换为应用图标资源
            .setSmallIcon(R.drawable.ic_mic_notification)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        // 点击通知打开应用主入口
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        launchIntent?.let {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                it,
                // Android 12+ 必须显式指定 FLAG_IMMUTABLE 或 FLAG_MUTABLE
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setContentIntent(pendingIntent)
        }

        // 添加"停止服务"动作按钮
        val stopIntent = Intent(this, WakeWordService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(
            // TODO: 实际项目中替换为停止图标资源
            R.drawable.ic_stop_notification,
            getString(R.string.wakeword_action_stop_service),
            stopPendingIntent
        )

        // 添加"结束会话"动作按钮（仅在会话活跃时显示）
        if (showEndSessionAction) {
            val endSessionIntent = Intent(this, WakeWordService::class.java).apply {
                action = ACTION_END_SESSION
            }
            val endPendingIntent = PendingIntent.getService(
                this,
                2,
                endSessionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                // TODO: 实际项目中替换为关闭图标资源
                R.drawable.ic_close_notification,
                getString(R.string.wakeword_action_end_session),
                endPendingIntent
            )
        }

        return builder.build()
    }

    /**
     * 更新前台通知内容
     * 会话状态变化时调用，刷新通知栏显示
     */
    private fun updateNotification(
        title: String,
        content: String,
        showEndSessionAction: Boolean = false
    ) {
        val notification = buildNotification(title, content, showEndSessionAction)
        notificationManager.notify(FOREGROUND_NOTIFICATION_ID, notification)
    }
}
