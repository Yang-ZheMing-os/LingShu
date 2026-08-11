package com.lingshu.feature.wakeword.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.lingshu.core.common.event.AppEvent
import com.lingshu.core.common.event.IAppEventBus
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.feature.wakeword.R
import com.lingshu.feature.wakeword.domain.IWakeWordEngine
import com.lingshu.feature.wakeword.domain.WakeWordEvent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class WakeWordService : Service() {

    @Inject
    lateinit var wakeWordEngine: IWakeWordEngine

    @Inject
    lateinit var eventBus: IAppEventBus

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var startJob: Job? = null

    private val wakeWordListener: (WakeWordEvent) -> Unit = { event ->
        val traceId = "ww_${System.currentTimeMillis()}"
        val stepTag = "[traceId=$traceId]"
        LingShuLog.i(
            "WakeWordService",
            "$stepTag 检测到唤醒词: keyword=${event.keyword}, timestamp=${event.timestamp}"
        )

        LingShuLog.d(
            "WakeWordService",
            "$stepTag [step-1] emit WakeWordDetected 事件到全局事件总线，触发后续 STT→对话 流程"
        )
        serviceScope.launch {
            eventBus.emit(
                AppEvent.WakeWordDetected(
                    keyword = event.keyword,
                    timestamp = event.timestamp,
                    traceId = traceId
                )
            )
            LingShuLog.v(
                "WakeWordService",
                "$stepTag [step-1] WakeWordDetected 事件已发出"
            )
        }

        sendBroadcast(Intent(ACTION_WAKE_WORD_DETECTED).apply {
            setPackage(packageName)
            putExtra(EXTRA_KEYWORD, event.keyword)
            putExtra(EXTRA_TIMESTAMP, event.timestamp)
            putExtra(EXTRA_TRACE_ID, traceId)
        })
        LingShuLog.d(
            "WakeWordService",
            "$stepTag [step-2] 已发送系统广播（兼容旧逻辑）"
        )
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        LingShuLog.d("WakeWordService", "服务已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startWakeWord()
            ACTION_STOP -> stopWakeWord()
        }
        return START_STICKY
    }

    private fun startWakeWord() {
        if (wakeWordEngine.isRunning()) return

        startJob?.cancel()
        startJob = serviceScope.launch {
            wakeWordEngine.registerListener(wakeWordListener)
            when (val result = wakeWordEngine.start()) {
                is com.lingshu.core.common.error.Result.Success -> {
                    LingShuLog.d("WakeWordService", "唤醒词引擎启动成功")
                    updateNotification(getString(R.string.wakeword_notification_running))
                }
                is com.lingshu.core.common.error.Result.Error -> {
                    LingShuLog.e(
                        "WakeWordService",
                        "唤醒词引擎启动失败: ${result.code}",
                        result.exception
                    )
                    updateNotification(getString(R.string.wakeword_notification_error))
                }
            }
        }
    }

    private fun stopWakeWord() {
        startJob?.cancel()
        serviceScope.launch {
            wakeWordEngine.unregisterListener(wakeWordListener)
            wakeWordEngine.stop()
            LingShuLog.d("WakeWordService", "唤醒词引擎已停止")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.wakeword_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.wakeword_notification_channel_desc)
                setShowBadge(false)
                enableVibration(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String = getString(R.string.wakeword_notification_running)): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.wakeword_notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    override fun onDestroy() {
        super.onDestroy()
        stopWakeWord()
        serviceScope.cancel()
        LingShuLog.d("WakeWordService", "服务已销毁")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.lingshu.feature.wakeword.action.START"
        const val ACTION_STOP = "com.lingshu.feature.wakeword.action.STOP"
        const val ACTION_WAKE_WORD_DETECTED = "com.lingshu.feature.wakeword.action.WAKE_WORD_DETECTED"
        const val EXTRA_KEYWORD = "extra_keyword"
        const val EXTRA_TIMESTAMP = "extra_timestamp"
        const val EXTRA_TRACE_ID = "extra_trace_id"

        private const val CHANNEL_ID = "wakeword_service"
        private const val NOTIFICATION_ID = 1001
    }
}
