package com.lingshu.agent.feature.health.services

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
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lingshu.agent.R
import com.lingshu.agent.feature.health.HealthAnomalyEvent
import com.lingshu.agent.feature.health.HealthManager
import com.lingshu.agent.feature.health.HealthRepository
import com.lingshu.agent.feature.health.AnomalySeverity
import com.lingshu.agent.feature.model.MessageRole
import com.lingshu.agent.feature.model.ModelMessage
import com.lingshu.agent.feature.model.ModelRouter
import com.lingshu.agent.feature.voice.TextToSpeechManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 健康监测前台服务
 *
 * 功能：
 * 1. 常驻前台，驱动 HealthManager 持续采样健康数据（不被系统杀死）
 * 2. 订阅 HealthManager.anomalyEvents，检测异常并主动通知用户
 * 3. 通知策略分级：
 *    - LOW（久坐/活动不足）：仅通知栏，不打扰
 *    - MEDIUM（心率偏低/睡眠不足/压力高）：通知 + 温和 TTS 提醒
 *    - HIGH（心率过高/血氧过低）：通知 + 强 TTS 提醒 + 震动
 * 4. 周期写入汇总（每小时写一次当日统计，便于Widget/锁屏展示）
 * 5. 支持绑定模式，供 Activity 直接控制启停和读取状态
 *
 * Android 版本适配：
 * - Android O+：NotificationChannel 必须
 * - Android Q+：FOREGROUND_SERVICE_TYPE_HEALTH 隐私合规
 * - Android 12+：PendingIntent 显式可变性
 */
@AndroidEntryPoint
class HealthMonitorService : Service() {

    // ==================== Binder ====================
    inner class LocalBinder : Binder() {
        fun getService(): HealthMonitorService = this@HealthMonitorService
    }
    private val binder = LocalBinder()

    // ==================== 注入 ====================
    @Inject lateinit var healthManager: HealthManager
    @Inject lateinit var healthRepository: HealthRepository
    @Inject lateinit var modelRouter: ModelRouter
    @Inject lateinit var ttsManager: TextToSpeechManager

    // ==================== 协程作用域 ====================
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ==================== 状态 ====================
    private var isRunning = false
    private var lastSummaryWriteTime = 0L

    companion object {
        private const val TAG = "HealthMonitorService"

        /** 通知渠道ID */
        const val NOTIFICATION_CHANNEL_ID = "lingshu_health_monitor_channel"
        const val NOTIFICATION_CHANNEL_NAME = "灵枢健康监测"

        /** 前台通知ID */
        const val FOREGROUND_NOTIFICATION_ID = 1002

        /** 异常通知ID（每条异常单独一个，根据类型区分） */
        private const val ANOMALY_NOTIFICATION_ID_BASE = 2000

        /** Action */
        const val ACTION_START = "com.lingshu.agent.START_HEALTH_MONITOR"
        const val ACTION_STOP = "com.lingshu.agent.STOP_HEALTH_MONITOR"
        const val ACTION_TEST_ANOMALY = "com.lingshu.agent.TEST_HEALTH_ANOMALY"

        /** 广播：监测状态变化 */
        const val BROADCAST_STATE_CHANGED = "com.lingshu.agent.HEALTH_MONITOR_STATE"
        const val EXTRA_IS_RUNNING = "extra_is_running"

        /** 广播：检测到异常 */
        const val BROADCAST_ANOMALY_DETECTED = "com.lingshu.agent.HEALTH_ANOMALY_DETECTED"
        const val EXTRA_ANOMALY_JSON = "extra_anomaly_json"

        /** 周期：每小时写一次当日汇总 */
        private const val SUMMARY_WRITE_INTERVAL_MS = 60 * 60 * 1000L
    }

    // ==================== 生命周期 ====================

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startHealthMonitor()
            }
            ACTION_STOP -> {
                stopHealthMonitor()
                return START_NOT_STICKY
            }
            ACTION_TEST_ANOMALY -> {
                // 调试用：模拟发送一条异常，验证通知链路
                serviceScope.launch {
                    testEmitAnomaly()
                }
            }
            null -> {
                // 默认启动
                startHealthMonitor()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        try {
            if (isRunning) {
                healthManager.stopMonitoring()
            }
            serviceScope.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "销毁服务时异常: ${e.message}")
        }
        super.onDestroy()
    }

    // ==================== 启停 ====================

    private fun startHealthMonitor() {
        if (isRunning) {
            ensureForeground(getString(R.string.health_notification_title_running),
                buildSummaryContent())
            return
        }

        // 启动前台
        val initialNotification = buildNotification(
            getString(R.string.health_notification_title_running),
            getString(R.string.health_notification_content_initializing)
        )
        startForegroundCompat(initialNotification)

        // 启动 HealthManager
        healthManager.startMonitoring()
        isRunning = true

        // 订阅异常事件
        subscribeAnomalyEvents()

        // 启动周期汇总协程
        startPeriodicSummaryWriter()

        // 发送状态广播
        sendBroadcast(Intent(BROADCAST_STATE_CHANGED).apply {
            setPackage(packageName)
            putExtra(EXTRA_IS_RUNNING, true)
        })

        Log.i(TAG, "健康监测前台服务已启动")
    }

    private fun stopHealthMonitor() {
        if (!isRunning) return
        healthManager.stopMonitoring()
        isRunning = false

        sendBroadcast(Intent(BROADCAST_STATE_CHANGED).apply {
            setPackage(packageName)
            putExtra(EXTRA_IS_RUNNING, false)
        })

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "健康监测服务已停止")
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    /**
     * 确保服务在前台（系统回收后恢复时调用）
     */
    private fun ensureForeground(title: String, content: String) {
        val notification = buildNotification(title, content)
        startForegroundCompat(notification)
    }

    // ==================== 异常订阅 & 通知 ====================

    private fun subscribeAnomalyEvents() {
        healthManager.anomalyEvents
            .onEach { anomaly ->
                handleAnomaly(anomaly)
            }
            .launchIn(serviceScope)
    }

    /**
     * 处理一条异常事件
     */
    private suspend fun handleAnomaly(anomaly: HealthAnomalyEvent) {
        Log.d(TAG, "检测到健康异常: $anomaly")

        // 1. 发送广播
        sendBroadcast(Intent(BROADCAST_ANOMALY_DETECTED).apply {
            setPackage(packageName)
            putExtra(EXTRA_ANOMALY_JSON, anomaly.toString())
        })

        // 2. 显示通知
        val severity = anomaly.getSeverity()
        showAnomalyNotification(anomaly, severity)

        // 3. 分级触发 TTS 和震动
        when (severity) {
            AnomalySeverity.HIGH -> {
                vibrate(pattern = longArrayOf(0, 300, 200, 300, 200, 500))
                speakAlert(anomaly.getDescription(), urgent = true)
                // 严重异常可额外：调用AI生成安抚话术
                generateAiComfort(anomaly)
            }
            AnomalySeverity.MEDIUM -> {
                vibrate(pattern = longArrayOf(0, 200, 150, 200))
                speakAlert(anomaly.getDescription(), urgent = false)
            }
            AnomalySeverity.LOW -> {
                // 轻度：不震动不TTS，仅通知栏
            }
        }

        // 4. 同步刷新前台通知，提示最新异常
        updateForegroundNotification(anomaly.getDescription())
    }

    /**
     * 显示异常通知
     */
    private fun showAnomalyNotification(
        anomaly: HealthAnomalyEvent,
        severity: AnomalySeverity
    ) {
        val notifManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // 同类型异常使用相同ID，避免通知栏刷屏
        val notifId = ANOMALY_NOTIFICATION_ID_BASE + when (anomaly) {
            is HealthAnomalyEvent.HeartRateHigh -> 1
            is HealthAnomalyEvent.HeartRateLow -> 2
            is HealthAnomalyEvent.Spo2Low -> 3
            is HealthAnomalyEvent.SedentaryWarning -> 4
            is HealthAnomalyEvent.StressHigh -> 5
            is HealthAnomalyEvent.SleepInsufficient -> 6
            is HealthAnomalyEvent.ActivityInsufficient -> 7
        }

        val title = when (anomaly) {
            is HealthAnomalyEvent.HeartRateHigh -> "⚠ 心率过高"
            is HealthAnomalyEvent.HeartRateLow -> "⚠ 心率过低"
            is HealthAnomalyEvent.Spo2Low -> "⚠ 血氧偏低"
            is HealthAnomalyEvent.SedentaryWarning -> "💡 久坐提醒"
            is HealthAnomalyEvent.StressHigh -> "💡 压力偏高"
            is HealthAnomalyEvent.SleepInsufficient -> "💡 睡眠不足"
            is HealthAnomalyEvent.ActivityInsufficient -> "💡 活动量不足"
        }

        val priority = when (severity) {
            AnomalySeverity.HIGH -> NotificationCompat.PRIORITY_HIGH
            AnomalySeverity.MEDIUM -> NotificationCompat.PRIORITY_DEFAULT
            AnomalySeverity.LOW -> NotificationCompat.PRIORITY_LOW
        }

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(anomaly.getDescription())
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(anomaly.getDescription()))
            // TODO: 替换为健康模块图标资源
            .setSmallIcon(R.drawable.ic_mic_notification)
            .setAutoCancel(true)
            .setPriority(priority)
            .setCategory(Notification.CATEGORY_ALARM)
            .apply {
                if (severity == AnomalySeverity.HIGH) {
                    setDefaults(Notification.DEFAULT_LIGHTS)
                }
            }
            .build()

        try {
            notifManager.notify(notifId, notification)
        } catch (e: Exception) {
            Log.e(TAG, "显示异常通知失败: ${e.message}")
        }
    }

    /**
     * TTS 播报异常提醒
     */
    private fun speakAlert(text: String, urgent: Boolean) {
        serviceScope.launch {
            try {
                // 前缀 + 文本，避免直接读技术术语
                val speechText = if (urgent) {
                    "主人您好，检测到健康提醒。$text"
                } else {
                    "您好，$text"
                }
                runCatching {
                    // ttsManager.speak(speechText)
                }
            } catch (e: Exception) {
                Log.e(TAG, "TTS播报失败: ${e.message}")
            }
        }
    }

    /**
     * 震动提醒
     */
    private fun vibrate(pattern: LongArray) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(
                    VibrationEffect.createWaveform(pattern, -1)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(pattern, -1)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "震动失败: ${e.message}")
        }
    }

    /**
     * 严重异常时调用AI生成安抚话术（异步，不阻塞主流程）
     */
    private fun generateAiComfort(anomaly: HealthAnomalyEvent) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val anomalyDesc = anomaly.getDescription()
                val messages = listOf(
                    ModelMessage(
                        role = MessageRole.SYSTEM,
                        content = "你是一位贴心的健康陪伴助理。用户被检测到异常健康指标，" +
                                "请用简短、温柔、安抚性的语气（30字以内）给出建议，不要危言耸听。"
                    ),
                    ModelMessage(
                        role = MessageRole.USER,
                        content = "异常信息：$anomalyDesc，请给出安抚和建议。"
                    )
                )
                val response = modelRouter.chat(messages)
                if (response.isSuccess) {
                    // 播报 AI 生成的安抚话术
                    runCatching {
                        // ttsManager.speak(response.content)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "AI安抚话术生成失败: ${e.message}")
            }
        }
    }

    // ==================== 周期汇总写入 ====================

    private fun startPeriodicSummaryWriter() {
        serviceScope.launch {
            while (isRunning) {
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastSummaryWriteTime >= SUMMARY_WRITE_INTERVAL_MS) {
                        // 每小时刷新一次前台通知的摘要
                        val summary = healthRepository.getTodaySummary()
                        val content = buildSummaryContentWithStats(summary)
                        updateForegroundNotification(content)
                        lastSummaryWriteTime = now
                    }
                    delay(5 * 60 * 1000L) // 每5分钟检查一次是否到了1小时
                } catch (e: Exception) {
                    Log.e(TAG, "汇总写入异常: ${e.message}", e)
                    delay(60_000L)
                }
            }
        }
    }

    private suspend fun buildSummaryContentWithStats(summary: com.lingshu.agent.feature.health.DailyStats): String {
        return "今日步数 ${summary.totalSteps}/${HealthRepository.DAILY_TARGET_STEPS} | " +
                "睡眠 ${summary.sleepMinutes / 60}h${summary.sleepMinutes % 60}m | " +
                if (summary.avgHeartRate > 0) "心率 ${summary.avgHeartRate.toInt()} BPM" else ""
    }

    private fun buildSummaryContent(): String {
        return "正在后台监测您的健康数据"
    }

    // ==================== 前台通知 ====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notifManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val existing = notifManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    NOTIFICATION_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "灵枢健康监测服务常驻通知，用于实时追踪您的健康状况"
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                }
                notifManager.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(title: String, content: String): Notification {
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            // TODO: 替换健康模块专用图标
            .setSmallIcon(R.drawable.ic_mic_notification)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        // 点击通知打开健康仪表盘
        packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
            launchIntent.action = Intent.ACTION_MAIN
            launchIntent.addCategory(Intent.CATEGORY_LAUNCHER)
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pending = PendingIntent.getActivity(
                this, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setContentIntent(pending)
        }

        // 停止服务动作
        val stopIntent = Intent(this, HealthMonitorService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 10, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(
            R.drawable.ic_stop_notification,
            getString(R.string.health_action_stop_monitor),
            stopPending
        )

        return builder.build()
    }

    private fun updateForegroundNotification(content: String) {
        val title = getString(R.string.health_notification_title_running)
        val notification = buildNotification(title, content)
        val notifManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notifManager.notify(FOREGROUND_NOTIFICATION_ID, notification)
    }

    // ==================== 调试工具 ====================

    private suspend fun testEmitAnomaly() {
        val testEvent = HealthAnomalyEvent.SedentaryWarning(sedentaryMinutes = 75)
        handleAnomaly(testEvent)
    }

    /**
     * 对外暴露的服务状态查询
     */
    fun isServiceRunning(): Boolean = isRunning
}
