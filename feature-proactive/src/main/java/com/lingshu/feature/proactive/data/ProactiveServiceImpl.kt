package com.lingshu.feature.proactive.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.lingshu.core.common.error.ErrorCodes
import com.lingshu.core.common.error.Result
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.feature.proactive.data.cooldown.CooldownManager
import com.lingshu.feature.proactive.data.generator.ContentGenerator
import com.lingshu.feature.proactive.data.trigger.TriggerEvaluator
import com.lingshu.feature.proactive.domain.IProactiveService
import com.lingshu.feature.proactive.domain.ProactiveConfig
import com.lingshu.feature.proactive.domain.ProactiveStatus
import com.lingshu.feature.proactive.domain.TriggerType
import com.lingshu.feature.proactive.worker.ProactiveCheckWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ProactiveServiceImpl(
    private val context: Context,
    private val appPreferences: com.lingshu.core.data.datastore.AppPreferences,
    private val triggerEvaluator: TriggerEvaluator,
    private val contentGenerator: ContentGenerator,
    private val cooldownManager: CooldownManager
) : IProactiveService {

    private val mutex = Mutex()
    private var currentConfig = ProactiveConfig()
    private var isLoaded = false

    init {
        LingShuLog.d(TAG, "ProactiveServiceImpl initialized")
    }

    private suspend fun ensureLoaded() = mutex.withLock {
        if (!isLoaded) {
            val stored = runCatching { cooldownManager.loadConfig() }
                .onFailure { LingShuLog.w(TAG, "加载持久化配置失败，使用默认值", it) }
                .getOrNull()
            if (stored != null) {
                currentConfig = stored
                LingShuLog.i(TAG, "已加载持久化配置: enabled=${stored.enabled} triggers=${stored.triggers.filter { it.value }.keys.joinToString { it.name }}")
            } else {
                LingShuLog.i(TAG, "无持久化配置，使用默认值: enabled=${currentConfig.enabled}")
            }
            isLoaded = true
        }
    }

    override suspend fun start() {
        ensureLoaded()
        ProactiveCheckWorker.start(context)
        LingShuLog.i(TAG, "Proactive service started, worker 已入队, enabled=${currentConfig.enabled}")
    }

    override suspend fun stop() {
        ProactiveCheckWorker.stop(context)
        triggerEvaluator.destroy()
        LingShuLog.i(TAG, "Proactive service stopped, worker 已取消")
    }

    override suspend fun configure(config: ProactiveConfig) {
        ensureLoaded()
        currentConfig = config
        runCatching { cooldownManager.saveConfig(config) }
            .onFailure { LingShuLog.e(TAG, "持久化配置失败", it) }
        LingShuLog.i(TAG, "配置更新并持久化: enabled=${config.enabled} cooldown=${config.cooldownMinutes}min maxPerDay=${config.maxPerDay}")
    }

    override suspend fun getConfig(): ProactiveConfig {
        ensureLoaded()
        return currentConfig
    }

    override suspend fun getStatus(): ProactiveStatus {
        ensureLoaded()
        val todayCount = cooldownManager.todayCount.first()
        val lastTime = cooldownManager.lastTriggerTime.first()
        val lastType = cooldownManager.lastTriggerType.first()
        return ProactiveStatus(
            isRunning = currentConfig.enabled,
            todayNotificationCount = todayCount,
            lastTriggerTime = lastTime,
            lastTriggerType = lastType
        )
    }

    override suspend fun checkAndNotify(): Result<Unit> {
        ensureLoaded()
        val now = java.util.Calendar.getInstance()
        val h = now.get(java.util.Calendar.HOUR_OF_DAY)
        val m = now.get(java.util.Calendar.MINUTE)
        LingShuLog.i(
            TAG,
            "========== checkAndNotify 开始（当前 ${h.toString().padStart(2,'0')}:${m.toString().padStart(2,'0')}） " +
                "enabled=${currentConfig.enabled} triggersON=${currentConfig.triggers.filter { it.value }.keys.joinToString { it.name }}"
        )

        if (!currentConfig.enabled) {
            LingShuLog.w(TAG, "❌ 跳过第1关：enabled=false，用户没开主动关怀开关 → 全流程停止")
            return Result.success(Unit)
        }
        LingShuLog.i(TAG, "✅ 第1关通过：开关已打开")

        val quietHit = triggerEvaluator.isInQuietHours(currentConfig.quietHours)
        if (quietHit) {
            val isLateNightZone = (h >= 23 && m >= 30) || h <= 4
            LingShuLog.i(
                TAG,
                "静音时段命中(${currentConfig.quietHours})：当前=$h:$m，LATE_NIGHT 窗口=$isLateNightZone"
            )
            if (!isLateNightZone) {
                LingShuLog.w(TAG, "❌ 跳过第2关：在静音时段且不是睡前窗口(23:30~05:00) → 停止")
                return Result.success(Unit)
            }
            LingShuLog.i(TAG, "✅ 第2关通过(静音放行)：睡前窗口 LATE_NIGHT 特殊放行")
        } else {
            LingShuLog.i(TAG, "✅ 第2关通过：不在静音时段")
        }

        val canTrigger = cooldownManager.canTrigger(currentConfig)
        val todayCount = cooldownManager.todayCount.first()
        val lastTime = cooldownManager.lastTriggerTime.first()
        LingShuLog.i(
            TAG,
            "冷却/上限检查：canTrigger=$canTrigger today=$todayCount/${currentConfig.maxPerDay} " +
                "cooldown=${currentConfig.cooldownMinutes}min lastTrigger=${if (lastTime == 0L) "never" else "${(System.currentTimeMillis() - lastTime) / 60000}min ago"}"
        )
        if (!canTrigger) {
            LingShuLog.w(TAG, "❌ 跳过第3关：冷却未到 or 当日上限(${currentConfig.maxPerDay})已满 → 停止")
            return Result.success(Unit)
        }
        LingShuLog.i(TAG, "✅ 第3关通过：冷却与当日上限满足")

        val triggerType = triggerEvaluator.evaluate(currentConfig.triggers)
            ?: run {
                LingShuLog.w(TAG, "❌ 跳过第4关：TriggerEvaluator.evaluate() 返回 null，当前时间无任何 trigger 命中 → 停止")
                LingShuLog.i(
                    TAG,
                    "   已开启的触发器：${currentConfig.triggers.filter { it.value }.keys.joinToString("、")}；" +
                        "MEAL：早餐07-09/午餐11:30-13:30/晚餐17:30-19:30；LATE_NIGHT：23:30~05:00"
                )
                return Result.success(Unit)
            }
        LingShuLog.i(TAG, "✅ 第4关通过：命中触发器 = $triggerType")

        return try {
            sendNotification(triggerType)
            cooldownManager.recordTrigger(triggerType)
            val content = contentGenerator.generate(triggerType)
            LingShuLog.i(TAG, "🎉 全部通关！通知发送成功: trigger=$triggerType title=\"${content.title}\" content=\"${content.content}\"")
            Result.success(Unit)
        } catch (e: Exception) {
            LingShuLog.e(TAG, "❌ 第5关失败：通知发送抛出异常 trigger=$triggerType", e)
            Result.error(ErrorCodes.UNKNOWN_ERROR, "通知发送失败", e)
        }
    }

    private fun sendNotification(triggerType: TriggerType) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "proactive_care_channel"

        // ===== Android 13+ 通知运行时权限检查 =====
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                LingShuLog.e(
                    TAG,
                    "❌ 无法发送通知：Android 13+ 未授予 POST_NOTIFICATIONS 权限" +
                        "（需在设置页引导用户手动开启通知权限）"
                )
                // 没权限就直接 return，再调 notify() 会是静默失败
                return
            }
            LingShuLog.d(TAG, "通知权限 POST_NOTIFICATIONS 已授予 ✅")
        } else {
            LingShuLog.d(TAG, "SDK ${Build.VERSION.SDK_INT} < 33，无需动态申请通知权限")
        }

        // DND（勿扰模式）判断：如果系统勿扰开了，提醒也会被静默，至少打个日志
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val dndInterruption = notificationManager.currentInterruptionFilter
            val dndOn = dndInterruption != NotificationManager.INTERRUPTION_FILTER_ALL
            if (dndOn) {
                LingShuLog.w(
                    TAG,
                    "⚠️ 系统勿扰模式(DND)已开启（filter=$dndInterruption），通知可能被静默；" +
                        "若收不到提醒，请到系统设置中给本应用开「允许打断」"
                )
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = notificationManager.getNotificationChannel(channelId)
            if (existing == null) {
                val channel = NotificationChannel(
                    channelId,
                    "主动关怀",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "主动关怀提醒通知（睡前/用餐/久坐等）"
                    enableVibration(true)
                    enableLights(true)
                    setShowBadge(true)
                }
                notificationManager.createNotificationChannel(channel)
                LingShuLog.i(TAG, "通知频道已创建: channelId=$channelId importance=HIGH")
            } else {
                LingShuLog.d(
                    TAG,
                    "通知频道已存在: importance=${existing.importance} " +
                        "canBypassDnd=${existing.canBypassDnd()} canShowBadge=${existing.canShowBadge()}"
                )
            }
        }

        val content = contentGenerator.generate(triggerType)
        LingShuLog.d(
            TAG,
            "开始构造通知: trigger=$triggerType title=\"${content.title}\""
        )

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
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_view, content.actionText, actionPendingIntent)
            .build()

        val notificationId = triggerType.ordinal + 1000
        notificationManager.notify(notificationId, notification)
        LingShuLog.i(TAG, "📤 已调用 NotificationManager.notify(id=$notificationId)，请查看通知栏")
    }

    override suspend fun sendTestNotificationNow(): Result<Unit> {
        LingShuLog.i(TAG, "🧪 调用测试通知：绕过 ALL 检查，直接发 LATE_NIGHT 样式通知")
        ensureLoaded()
        return try {
            // 测试通知统一走睡前提醒样式，用户最关心这个
            sendNotification(TriggerType.LATE_NIGHT)
            LingShuLog.i(TAG, "🧪 测试通知发送完成")
            Result.success(Unit)
        } catch (e: Exception) {
            LingShuLog.e(TAG, "🧪 测试通知发送失败", e)
            Result.error(ErrorCodes.UNKNOWN_ERROR, "测试通知发送失败", e)
        }
    }

    companion object {
        private const val TAG = "Proactive"
    }
}
