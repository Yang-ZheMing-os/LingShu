package com.lingshu.feature.proactive.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.lingshu.core.common.error.ErrorCodes
import com.lingshu.core.common.error.Result
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.core.data.datastore.AppPreferences
import com.lingshu.feature.proactive.data.cooldown.CooldownManager
import com.lingshu.feature.proactive.data.generator.ContentGenerator
import com.lingshu.feature.proactive.data.trigger.TriggerEvaluator
import com.lingshu.feature.proactive.domain.IProactiveService
import com.lingshu.feature.proactive.domain.ProactiveConfig
import com.lingshu.feature.proactive.domain.ProactiveStatus
import com.lingshu.feature.proactive.domain.TriggerType
import com.lingshu.feature.proactive.worker.ProactiveCheckWorker
import kotlinx.coroutines.flow.first

class ProactiveServiceImpl(
    private val context: Context,
    private val appPreferences: AppPreferences,
    private val triggerEvaluator: TriggerEvaluator,
    private val contentGenerator: ContentGenerator,
    private val cooldownManager: CooldownManager
) : IProactiveService {

    private var currentConfig = ProactiveConfig()
    private var isRunning = false

    init {
        LingShuLog.d("Proactive", "ProactiveServiceImpl initialized")
    }

    override suspend fun start() {
        if (isRunning) return
        isRunning = true
        ProactiveCheckWorker.start(context)
        LingShuLog.i("Proactive", "Proactive service started")
    }

    override suspend fun stop() {
        isRunning = false
        ProactiveCheckWorker.stop(context)
        triggerEvaluator.destroy()
        LingShuLog.i("Proactive", "Proactive service stopped")
    }

    override suspend fun configure(config: ProactiveConfig) {
        currentConfig = config
        LingShuLog.d("Proactive", "Config updated: enabled=${config.enabled}")
    }

    override suspend fun getConfig(): ProactiveConfig {
        return currentConfig
    }

    override suspend fun getStatus(): ProactiveStatus {
        val todayCount = cooldownManager.todayCount.first()
        val lastTime = cooldownManager.lastTriggerTime.first()
        val lastType = cooldownManager.lastTriggerType.first()
        return ProactiveStatus(
            isRunning = isRunning,
            todayNotificationCount = todayCount,
            lastTriggerTime = lastTime,
            lastTriggerType = lastType
        )
    }

    override suspend fun checkAndNotify(): Result<Unit> {
        if (!isRunning || !currentConfig.enabled) {
            return Result.success(Unit)
        }

        if (triggerEvaluator.isInQuietHours(currentConfig.quietHours)) {
            LingShuLog.d("Proactive", "In quiet hours, skip notification")
            return Result.success(Unit)
        }

        if (!cooldownManager.canTrigger(currentConfig)) {
            LingShuLog.d("Proactive", "Cooldown or daily limit reached")
            return Result.success(Unit)
        }

        val triggerType = triggerEvaluator.evaluate(currentConfig.triggers)
            ?: return Result.success(Unit)

        return try {
            sendNotification(triggerType)
            cooldownManager.recordTrigger(triggerType)
            LingShuLog.i("Proactive", "Notification sent for $triggerType")
            Result.success(Unit)
        } catch (e: Exception) {
            LingShuLog.e("Proactive", "Failed to send notification", e)
            Result.error(ErrorCodes.UNKNOWN_ERROR, "通知发送失败", e)
        }
    }

    private fun sendNotification(triggerType: TriggerType) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "proactive_care_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "主动关怀",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "主动关怀提醒通知"
                enableVibration(true)
                enableLights(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val content = contentGenerator.generate(triggerType)

        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val actionIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = "com.lingshu.proactive.ACTION_${triggerType.name}"
        }
        val actionPendingIntent = PendingIntent.getBroadcast(
            context,
            triggerType.ordinal,
            actionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(content.title)
            .setContentText(content.content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_view, content.actionText, actionPendingIntent)
            .build()

        notificationManager.notify(triggerType.ordinal + 1000, notification)
    }
}
