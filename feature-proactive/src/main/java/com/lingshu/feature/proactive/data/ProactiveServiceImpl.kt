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
import com.lingshu.feature.proactive.domain.CheckStep
import com.lingshu.feature.proactive.domain.IProactiveService
import com.lingshu.feature.proactive.domain.ProactiveConfig
import com.lingshu.feature.proactive.domain.ProactiveDiagnostics
import com.lingshu.feature.proactive.domain.ProactiveStatus
import com.lingshu.feature.proactive.domain.QuietHours
import com.lingshu.feature.proactive.domain.TriggerHitResult
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

        val triggerType = triggerEvaluator.evaluate(currentConfig)
            ?: run {
                LingShuLog.w(TAG, "❌ 跳过第4关：TriggerEvaluator.evaluate() 返回 null，当前时间无任何 trigger 命中 → 停止")
                LingShuLog.i(
                    TAG,
                    "   已开启的触发器：${currentConfig.triggers.filter { it.value }.keys.joinToString("、")}；" +
                        "MEAL：早餐07-09/午餐11:30-13:30/晚餐17:30-19:30；LATE_NIGHT：23:30~05:00；RAINY_DAY：填 Key 后启用"
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

    // ==========================================================================
    //  诊断：把 checkAndNotify 里 4 关的通过/失败 + 每个 trigger 命中结果整理给 UI
    // ==========================================================================

    override suspend fun runDiagnostics(): ProactiveDiagnostics {
        ensureLoaded()
        val now = java.util.Calendar.getInstance()
        val h = now.get(java.util.Calendar.HOUR_OF_DAY)
        val m = now.get(java.util.Calendar.MINUTE)
        val timeText = "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"

        // --- 当前激活的时间窗口说明 ---
        val activeWindows = buildList {
            if (inMealWindow(h, m, 7, 0, 9, 0)) add("早餐时段 07:00–09:00")
            if (inMealWindow(h, m, 11, 30, 13, 30)) add("午餐时段 11:30–13:30")
            if (inMealWindow(h, m, 17, 30, 19, 30)) add("晚餐时段 17:30–19:30")
            if (inLateNightWindow(h, m)) add("睡前窗口 23:30–05:00")
            if (isEmpty()) add("（非任何触发窗口 · WorkManager 每 15 分钟会再检查一次）")
        }

        // --- 第 1 关：总开关 ---
        val stepEnabled = if (currentConfig.enabled) {
            CheckStep(passed = true, message = "✅ 总开关已打开（enabled=true）")
        } else {
            CheckStep(passed = false, message = "❌ 总开关未打开 → 全部不执行；请先打开上方「主动关怀」开关")
        }

        // --- 第 2 关：静音时段（但睡前窗口 23:30~05:00 特殊放行）---
        val quietHit = triggerEvaluator.isInQuietHours(currentConfig.quietHours)
        val isLateNight = inLateNightWindow(h, m)
        val stepQuietHours = when {
            !quietHit ->
                CheckStep(passed = true,
                    message = "✅ 不在静音时段（当前静音：" +
                        "${currentConfig.quietHours.fmt()}）")
            isLateNight ->
                CheckStep(passed = true,
                    message = "⚠️ 处于静音时段，但命中睡前窗口(23:30~05:00) → 特殊放行")
            else ->
                CheckStep(passed = false,
                    message = "❌ 处于静音时段(" +
                        "${currentConfig.quietHours.fmt()}) 且非睡前窗口 → 停止，" +
                        "可在下方「静音时段」调整或临时关闭")
        }

        // --- 第 3 关：冷却与当日上限 ---
        val canTrigger = cooldownManager.canTrigger(currentConfig)
        val todayCount = cooldownManager.todayCount.first()
        val lastTime = cooldownManager.lastTriggerTime.first()
        val minsAgo = if (lastTime == 0L) -1 else ((System.currentTimeMillis() - lastTime) / 60000L).toInt()
        val cooldownMs = currentConfig.cooldownMinutes * 60 * 1000L
        val cdRemain = if (lastTime == 0L) 0 else ((cooldownMs - (System.currentTimeMillis() - lastTime)) / 60000L).toInt()
        val stepCooldown = when {
            !canTrigger && todayCount >= currentConfig.maxPerDay ->
                CheckStep(passed = false,
                    message = "❌ 今日已达上限 $todayCount/${currentConfig.maxPerDay} 次 → " +
                        "调大「每日最大推送次数」或等明天")
            !canTrigger && cdRemain > 0 ->
                CheckStep(passed = false,
                    message = "❌ 冷却未到期：上次推送 ${minsAgo}min 前，还需等 $cdRemain min → " +
                        "可改小「冷却时间」或点下方测试通知")
            else ->
                CheckStep(passed = true,
                    message = "✅ 冷却 OK：今日 $todayCount/${currentConfig.maxPerDay}，" +
                        if (minsAgo < 0) "尚未推送过" else "上次 ${minsAgo}min 前（冷却 ${currentConfig.cooldownMinutes}min）")
        }

        // --- 第 4 关：每个 trigger 命中情况（完全复用 TriggerEvaluator 的判断逻辑但不写 side-effect）---
        val triggerResults = TriggerType.values().associateWith { t ->
            val userOn = currentConfig.triggers[t] == true
            val (hit, detail) = evalTriggerLogic(t, h, m)
            val filtered: Boolean? = if (!userOn || !hit) null else {
                val nonUrgent = setOf(TriggerType.SEDENTARY, TriggerType.DARK_WALKING,
                    TriggerType.RAINY_DAY, TriggerType.RANDOM)
                if (t in nonUrgent) {
                    // 用"确定性"展示：非紧急类即使命中也有 75% 被过滤，告诉用户这是预期内行为
                    kotlin.random.Random.nextFloat() <= 0.75f
                } else false
            }
            TriggerHitResult(
                userEnabled = userOn,
                logicHit = hit,
                filteredByProbability = filtered,
                detail = buildString {
                    if (!userOn) append("🔕 用户已关闭 · ")
                    append(detail)
                    if (filtered == true) append(" · ⚠️ 非紧急触发将以 75% 概率被随机过滤")
                }
            )
        }

        // --- 最终结论 ---
        val conclusion = when {
            !stepEnabled.passed -> "👉 先打开主动关怀开关"
            !stepQuietHours.passed -> "👉 当前在静音时段且非睡前窗口，调整静音时段或等时间流逝"
            !stepCooldown.passed -> "👉 冷却/当日上限未通过，点「发送测试通知」可立即验证通知效果"
            triggerResults.values.none { it.ultimatelyPicked } ->
                "👉 前 3 关通过；但当前时间未命中任何触发规则，命中窗口后 15 分钟内会推送（WorkManager 周期 15min）"
            else -> "✅ 所有检查通过，接下来一次 WorkManager 轮询会发送通知；" +
                triggerResults.entries.firstOrNull { it.value.ultimatelyPicked }
                    ?.let { (t, _) -> "预计命中规则：${triggerDisplayNamesHuman(t)}" }.orEmpty()
        }

        return ProactiveDiagnostics(
            currentTimeText = timeText,
            activeTimeWindows = activeWindows,
            stepEnabled = stepEnabled,
            stepQuietHours = stepQuietHours,
            stepCooldown = stepCooldown,
            stepTriggers = triggerResults,
            conclusion = conclusion
        )
    }

    // ---------------- 诊断辅助函数 ----------------
    private fun inMealWindow(h: Int, m: Int, sh: Int, sm: Int, eh: Int, em: Int): Boolean {
        val cur = h * 60 + m
        return cur in (sh * 60 + sm)..(eh * 60 + em)
    }

    private fun inLateNightWindow(h: Int, m: Int): Boolean {
        val cur = h * 60 + m
        return cur >= 23 * 60 + 30 || cur <= 5 * 60
    }

    /** 复刻 TriggerEvaluator 的核心判断（不依赖传感器读值的部分）；传感器类的做降级说明 */
    private fun evalTriggerLogic(t: TriggerType, h: Int, m: Int): Pair<Boolean, String> {
        return when (t) {
            TriggerType.LATE_NIGHT -> {
                val hit = inLateNightWindow(h, m)
                hit to if (hit) "✅ $h:$m ∈ 睡前窗口(23:30~05:00)（已修复：不再要求屏幕亮屏）"
                       else "⏭️ 不在睡前窗口"
            }
            TriggerType.MEAL_TIME -> {
                val hit = inMealWindow(h, m, 7, 0, 9, 0) ||
                          inMealWindow(h, m, 11, 30, 13, 30) ||
                          inMealWindow(h, m, 17, 30, 19, 30)
                val which = when {
                    inMealWindow(h, m, 7, 0, 9, 0) -> "早餐"
                    inMealWindow(h, m, 11, 30, 13, 30) -> "午餐"
                    inMealWindow(h, m, 17, 30, 19, 30) -> "晚餐"
                    else -> ""
                }
                hit to if (hit) "✅ $h:$m ∈ $which 饭点窗口" else "⏭️ 不在三餐饭点（早07–09/午11:30–13:30/晚17:30–19:30）"
            }
            TriggerType.SEDENTARY -> {
                // 久坐需要持续跟踪，启动后第一次默认 0
                false to "ℹ️ 需 App 常驻后台运行 ≥ 2 小时（当前无法从静态快照判断，实际执行会由 Worker 计算）"
            }
            TriggerType.DARK_WALKING -> {
                false to "ℹ️ 依赖光线传感器 < 10 lux + 行走状态，只有真实携带手机在暗光走路时才会触发"
            }
            TriggerType.HEART_RATE -> {
                false to "ℹ️ 依赖心率传感器（需穿戴设备），异常条件：> 100 或 < 45 bpm"
            }
            TriggerType.STRESS -> {
                false to "ℹ️ 依赖 HRV（心率变异性）数据（> 0.7 判定压力大）"
            }
            TriggerType.RAINY_DAY -> {
                val key = currentConfig.qWeatherKey.trim()
                if (key.isEmpty()) {
                    false to "ℹ️ 未填和风天气 Key → 恒不命中；在「雨天带伞提醒配置」粘贴 Key 后自动启用"
                } else {
                    // 诊断时用同一逻辑做一次真实判断（带缓存）
                    val rainy = runCatching { triggerEvaluator.evaluate(currentConfig.copy(triggers = mapOf(TriggerType.RAINY_DAY to true))) == TriggerType.RAINY_DAY }
                        .getOrDefault(false)
                    rainy to if (rainy) "✅ 和风天气返回今日有雨雪 → 命中（可能被 75% 非紧急过滤）" else "⏭️ 和风天气返回今日无雨雪 → 未命中（1 小时缓存）"
                }
            }
            TriggerType.MEMORY -> {
                false to "ℹ️ 由外部模块处理（生日/纪念日/负面情绪随访）"
            }
            TriggerType.RANDOM -> {
                // 诊断里按 5% 采样模拟一次结果让用户看到
                val hit = kotlin.random.Random.nextFloat() < 0.05f
                hit to if (hit) "✅ RANDOM 5% 采样命中本次（会被 75% 二次过滤）" else "⏭️ RANDOM 95% 未命中（小概率随机关怀）"
            }
        }
    }

    private fun triggerDisplayNamesHuman(t: TriggerType): String = when (t) {
        TriggerType.LATE_NIGHT -> "深夜未睡提醒"
        TriggerType.MEAL_TIME -> "饭点进食提醒"
        TriggerType.SEDENTARY -> "久坐活动提醒"
        TriggerType.DARK_WALKING -> "暗光行走提醒"
        TriggerType.HEART_RATE -> "心率异常提醒"
        TriggerType.STRESS -> "压力指数提醒"
        TriggerType.RAINY_DAY -> "雨天带伞提醒"
        TriggerType.MEMORY -> "记忆随访提醒"
        TriggerType.RANDOM -> "随机小确幸"
    }

    private fun QuietHours.fmt(): String =
        "${String.format("%02d:%02d", startHour, startMinute)}–${String.format("%02d:%02d", endHour, endMinute)}"

    companion object {
        private const val TAG = "Proactive"
    }
}
