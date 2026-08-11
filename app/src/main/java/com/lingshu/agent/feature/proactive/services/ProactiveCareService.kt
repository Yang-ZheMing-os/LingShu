package com.lingshu.agent.feature.proactive.services

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.util.Log
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.lingshu.agent.LingShuApp
import com.lingshu.agent.feature.proactive.CustomReminder
import com.lingshu.agent.feature.proactive.ProactiveCareRepository
import com.lingshu.agent.feature.proactive.ProactiveConfig
import com.lingshu.agent.feature.proactive.ProactiveContentGenerator
import com.lingshu.agent.feature.proactive.ProactiveDecisionEngine
import com.lingshu.agent.feature.proactive.ProactiveTriggers
import com.lingshu.agent.feature.proactive.TriggerResult
import com.lingshu.agent.feature.proactive.TriggerType
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import androidx.hilt.work.HiltWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * 主动关怀前台服务
 *
 * 核心职责：
 * 1. 以后台常驻服务形式运行，保证主动关怀功能不被系统杀死
 * 2. 通过 WorkManager 定期（默认15分钟）执行触发条件检测
 * 3. 通过 AlarmManager 实现精确的固定时间点提醒（早安/晚安/自定义提醒）
 * 4. 检测到需要触发关怀时：生成内容 → 发送通知 → 可选直接启动对话
 * 5. 维护通知栏常驻状态，展示运行状态信息
 *
 * 服务启动流程：
 * - 开启总开关时：startService(ACTION_START) → startForeground → 调度WorkManager/AlarmManager
 * - 关闭总开关时：startService(ACTION_STOP) → 停止调度 → stopForeground + stopSelf
 * - 解锁/传感器事件时：startService(ACTION_CHECK_NOW) → 立即执行一次检测
 *
 * 通知渠道使用 LingShuApp 中统一创建的：
 * - 常驻服务：LingShuApp.CHANNEL_FOREGROUND（低优先级，避免打扰）
 * - 关怀事件弹窗：LingShuApp.CHANNEL_PROACTIVE（高优先级，横幅通知）
 */
@AndroidEntryPoint
class ProactiveCareService : Service() {

    // ==================== 依赖注入 ====================

    @Inject lateinit var config: ProactiveConfig
    @Inject lateinit var repository: ProactiveCareRepository
    @Inject lateinit var decisionEngine: ProactiveDecisionEngine
    @Inject lateinit var contentGenerator: ProactiveContentGenerator

    // ==================== 内部状态 ====================

    /** 协程作用域（绑定服务生命周期） */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 当前正在执行的检测Job */
    private var currentCheckJob: Job? = null

    /** 通知管理器 */
    private lateinit var notificationManager: NotificationManager

    /** 服务是否已启动前台 */
    private var isForegroundRunning = false

    // ==================== 常量定义 ====================

    companion object {
        // ---------- 通知相关 ----------
        /** 常驻通知ID（不能与其他通知重复） */
        const val FOREGROUND_NOTIFICATION_ID = 2001
        /** 关怀事件通知起始ID（自增，避免覆盖） */
        private var careEventNotificationId = 3000

        // ---------- Service Action ----------
        const val ACTION_START = "com.lingshu.agent.proactive.START"
        const val ACTION_STOP = "com.lingshu.agent.proactive.STOP"
        const val ACTION_CHECK_NOW = "com.lingshu.agent.proactive.CHECK_NOW"
        const val ACTION_NOTIFICATION_CLICKED = "com.lingshu.agent.proactive.NOTIFICATION_CLICKED"
        const val ACTION_NOTIFICATION_DISMISSED = "com.lingshu.agent.proactive.NOTIFICATION_DISMISSED"

        // ---------- Intent Extra Key ----------
        const val EXTRA_CARE_RECORD_ID = "extra_care_record_id"
        const val EXTRA_CARE_CONTENT = "extra_care_content"
        const val EXTRA_TRIGGER_TYPE = "extra_trigger_type"

        // ---------- WorkManager ----------
        const val WORK_NAME_PERIODIC_CHECK = "proactive_care_periodic_check"
        /** WorkManager 最小支持 15 分钟，不能再短 */
        const val DEFAULT_CHECK_INTERVAL_MINUTES = 15

        // ---------- AlarmManager ----------
        private const val ALARM_REQUEST_CODE_BASE = 4000

        // ---------- 广播 ----------
        /** 广播 Action：主动关怀已触发（供 UI 层订阅启动对话） */
        const val ACTION_BROADCAST_CARE_TRIGGERED = "com.lingshu.agent.proactive.CARE_TRIGGERED"
    }

    // ==================== 生命周期 ====================

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        ensureNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startProactiveCare()
            ACTION_STOP -> stopProactiveCare()
            ACTION_CHECK_NOW -> performCheckNow()
            ACTION_NOTIFICATION_CLICKED -> handleNotificationClicked(intent)
            ACTION_NOTIFICATION_DISMISSED -> handleNotificationDismissed(intent)
            null -> {
                // 系统重启/被杀后恢复启动，默认走 START 流程
                startProactiveCare()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            currentCheckJob?.cancel()
            serviceScope.cancel()
        } catch (_: Throwable) {
            // 忽略销毁阶段的异常
        }
        super.onDestroy()
    }

    // ==================== 启动/停止 ====================

    /**
     * 启动主动关怀服务
     */
    private fun startProactiveCare() {
        serviceScope.launch {
            // 启动前台服务通知（必须在 5s 内调用 startForeground，否则 ANR）
            startForegroundNotification(
                title = safeGetString("主动关怀运行中", "proactive_notification_title_idle"),
                content = safeGetString("待机中，未触发关怀", "proactive_notification_content_idle")
            )
            isForegroundRunning = true

            // 调度定期检测 + 精确时间提醒
            runCatching { schedulePeriodicWork() }
            runCatching { scheduleExactReminders() }

            // 启动时立即执行一次检测
            performCheckInternal()
        }
    }

    /**
     * 停止主动关怀服务
     */
    private fun stopProactiveCare() {
        runCatching {
            WorkManager.getInstance(this).cancelUniqueWork(WORK_NAME_PERIODIC_CHECK)
        }
        runCatching { cancelExactReminders() }

        isForegroundRunning = false
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        stopSelf()
    }

    // ==================== 触发检测 ====================

    /**
     * 立即执行一次检测（外部调用入口）
     */
    private fun performCheckNow() {
        if (!isForegroundRunning) {
            startForegroundNotification(
                title = safeGetString("主动关怀检测中…", "proactive_notification_title_checking"),
                content = safeGetString("正在检查触发条件", "proactive_notification_content_checking")
            )
        } else {
            updateForegroundNotification(
                title = safeGetString("主动关怀检测中…", "proactive_notification_title_checking"),
                content = safeGetString("正在检查触发条件", "proactive_notification_content_checking")
            )
        }
        performCheckInternal()
    }

    /**
     * 内部执行检测的核心逻辑
     *
     * 流程：
     * 1. preflightCheck（总开关、冷却、每日上限）
     * 2. buildSnapshot → shouldTrigger(TriggerSnapshot) → TriggerResult
     * 3. 如通过 → generateCareContent → 发送通知 + 存记录 + 可选启动对话
     */
    private fun performCheckInternal() {
        currentCheckJob?.cancel()
        currentCheckJob = serviceScope.launch {
            runCatching {
                // 1. 前置检查（总开关 / 冷却 / 每日上限）
                val (canProceed, reason) = decisionEngine.preflightCheck()
                if (!canProceed) {
                    updateForegroundNotification(
                        title = safeGetString("主动关怀运行中", "proactive_notification_title_idle"),
                        content = "待机中 · $reason"
                    )
                    return@launch
                }

                // 2. 执行决策（使用默认空快照 + 仓储数据自动填充）
                val snapshot = decisionEngine.buildSnapshot()
                val triggerResult: TriggerResult = decisionEngine.shouldTrigger(snapshot)
                if (!triggerResult.shouldTrigger || triggerResult.triggerType == null) {
                    updateForegroundNotification(
                        title = safeGetString("主动关怀运行中", "proactive_notification_title_idle"),
                        content = safeGetString("待机中，未触发关怀", "proactive_notification_content_idle")
                    )
                    return@launch
                }
                val triggerType: TriggerType = triggerResult.triggerType

                // 3. 生成关怀内容（RuleBased / ModelBased / Hybrid，由 ProactiveConfig 控制）
                val careText: String = runCatching {
                    contentGenerator.generateCareContent(
                        trigger = triggerResult,
                        persona = null,
                        recentContext = null
                    )
                }.getOrElse {
                    Log.w("ProactiveCareService", "内容生成异常，回退默认文案", it)
                    "记得照顾好自己哦~"
                }

                // 4. 写入关怀记录（同步更新冷却、今日计数、历史）
                val recordId: String = repository.recordCareTriggered(
                    triggerType = triggerType,
                    triggerReason = triggerResult.reason,
                    careContent = careText,
                    triggerData = mapOf(
                        "confidence" to triggerResult.confidence,
                        "hourOfDay" to snapshot.currentHour,
                        "unlocksToday" to snapshot.unlockCountSinceMorning
                    )
                )

                // 规格书 P5：记录按触发类型的上次触发时间（用于间隔控制）
                repository.setLastTriggerTime(triggerType)

                // 5. 发送关怀事件通知（横幅，用户可点击/滑掉）
                sendCareEventNotification(
                    triggerType = triggerType,
                    content = careText,
                    recordId = recordId
                )

                // 6. 自动打开聊天（按配置决定）
                val autoLaunch = config.autoLaunchChat.first()
                if (autoLaunch) {
                    broadcastCareTriggered(triggerType, careText, recordId)
                }

                // 7. 更新常驻通知状态
                val displayName = triggerType.displayName
                val preview = careText.take(24).let { if (careText.length > 24) "$it…" else it }
                updateForegroundNotification(
                    title = safeGetString("刚刚发送了一条关怀", "proactive_notification_title_triggered"),
                    content = "$displayName · $preview"
                )

            }.onFailure { e ->
                Log.w("ProactiveCareService", "performCheckInternal 异常", e)
                updateForegroundNotification(
                    title = safeGetString("主动关怀异常", "proactive_notification_title_error"),
                    content = "检测异常：${e.message?.take(40) ?: "未知错误"}"
                )
            }
        }
    }

    // ==================== WorkManager 定期检测调度 ====================

    /**
     * 调度定期检测 Work（每 15 分钟一次）
     */
    private fun schedulePeriodicWork() {
        val workRequest = PeriodicWorkRequestBuilder<ProactiveCareCheckWorker>(
            repeatInterval = DEFAULT_CHECK_INTERVAL_MINUTES.toLong(),
            repeatIntervalTimeUnit = TimeUnit.MINUTES,
            flexTimeInterval = 5,
            flexTimeIntervalUnit = TimeUnit.MINUTES
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(false)
                    .setRequiresCharging(false)
                    .setRequiresDeviceIdle(false)
                    .build()
            )
            .addTag(WORK_NAME_PERIODIC_CHECK)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            WORK_NAME_PERIODIC_CHECK,
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
    }

    // ==================== AlarmManager 精确时间提醒 ====================

    /**
     * 调度所有自定义提醒时间的精确 Alarm
     */
    private fun scheduleExactReminders() {
        serviceScope.launch {
            val reminders: List<CustomReminder> = config.getCustomReminders().filter { it.enabled }
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager

            reminders.forEachIndexed { index, reminder ->
                val requestCode = ALARM_REQUEST_CODE_BASE + index
                val triggerAtMillis = calculateNextReminderTime(reminder)

                val intent = Intent(this@ProactiveCareService, ProactiveCareService::class.java)
                    .apply { action = ACTION_CHECK_NOW }
                val pendingIntent = PendingIntent.getService(
                    this@ProactiveCareService,
                    requestCode,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerAtMillis,
                            pendingIntent
                        )
                    } else {
                        alarmManager.setExact(
                            AlarmManager.RTC_WAKEUP,
                            triggerAtMillis,
                            pendingIntent
                        )
                    }
                }
            }
        }
    }

    /**
     * 计算下一次提醒的绝对时间戳（以 ProactiveConfig.CustomReminder 为数据源）
     */
    private fun calculateNextReminderTime(reminder: CustomReminder): Long {
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, reminder.hour)
            set(Calendar.MINUTE, reminder.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // 如果今天的这个时间已经过了，加一天
        if (calendar.timeInMillis <= now) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }

        // 如果指定了星期几，找到下一个匹配的日期
        if (reminder.daysOfWeek.isNotEmpty()) {
            while (calendar.get(Calendar.DAY_OF_WEEK) !in reminder.daysOfWeek) {
                calendar.add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return calendar.timeInMillis
    }

    /**
     * 取消所有精确提醒 Alarm（一次性最多取消 50 个，足够日常使用）
     */
    private fun cancelExactReminders() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (i in 0 until 50) {
            val requestCode = ALARM_REQUEST_CODE_BASE + i
            val intent = Intent(this, ProactiveCareService::class.java)
                .apply { action = ACTION_CHECK_NOW }
            val pendingIntent = PendingIntent.getService(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            runCatching { alarmManager.cancel(pendingIntent) }
        }
    }

    // ==================== 通知相关 ====================

    /**
     * 确保通知渠道存在（复用 LingShuApp 中统一创建的渠道，缺失时本函数兜底再建一次）
     */
    private fun ensureNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existingService = notificationManager.getNotificationChannel(LingShuApp.CHANNEL_FOREGROUND)
            if (existingService == null) {
                val serviceChannel = NotificationChannel(
                    LingShuApp.CHANNEL_FOREGROUND,
                    "灵枢前台服务",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "灵枢应用的常驻服务通知"
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                }
                notificationManager.createNotificationChannel(serviceChannel)
            }

            val existingEvent = notificationManager.getNotificationChannel(LingShuApp.CHANNEL_PROACTIVE)
            if (existingEvent == null) {
                val eventChannel = NotificationChannel(
                    LingShuApp.CHANNEL_PROACTIVE,
                    "灵枢主动关怀",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "灵枢的主动关怀提醒（深夜/久坐/生日等）"
                    setShowBadge(true)
                    enableLights(true)
                    enableVibration(true)
                }
                notificationManager.createNotificationChannel(eventChannel)
            }
        }
    }

    /**
     * 启动前台服务通知（必须在 5 秒内调用）
     */
    private fun startForegroundNotification(title: String, content: String) {
        val notification = buildServiceNotification(title, content)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    private fun updateForegroundNotification(title: String, content: String) {
        val notification = buildServiceNotification(title, content)
        notificationManager.notify(FOREGROUND_NOTIFICATION_ID, notification)
    }

    /**
     * 构建常驻服务通知
     *
     * 注意：drawable 引用优先使用项目内图标，
     * 如 ic_heart_notification / ic_refresh_notification / ic_stop_notification 不存在，
     * 会自动降级使用 Android 系统图标避免编译失败；
     * 请后续在 R.drawable 中补充同名 SVG/PNG 图标替换。
     */
    private fun buildServiceNotification(title: String, content: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent: PendingIntent? = launchIntent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val stopIntent = Intent(this, ProactiveCareService::class.java)
            .apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val checkIntent = Intent(this, ProactiveCareService::class.java)
            .apply { action = ACTION_CHECK_NOW }
        val checkPendingIntent = PendingIntent.getService(
            this, 2, checkIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val smallIcon = resolveIcon(
            custom = "ic_heart_notification",
            fallback = android.R.drawable.ic_menu_view
        )
        val refreshIcon = resolveIcon(
            custom = "ic_refresh_notification",
            fallback = android.R.drawable.ic_menu_rotate
        )
        val stopIcon = resolveIcon(
            custom = "ic_stop_notification",
            fallback = android.R.drawable.ic_menu_close_clear_cancel
        )

        return NotificationCompat.Builder(this, LingShuApp.CHANNEL_FOREGROUND)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(smallIcon)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .addAction(
                refreshIcon,
                safeGetString("立即检测", "proactive_action_check_now"),
                checkPendingIntent
            )
            .addAction(
                stopIcon,
                safeGetString("停止关怀", "proactive_action_stop_service"),
                stopPendingIntent
            )
            .build()
    }

    /**
     * 发送关怀事件通知（横幅，点击/删除都会被追踪）
     */
    private fun sendCareEventNotification(
        triggerType: TriggerType,
        content: String,
        recordId: String
    ) {
        val notificationId = ++careEventNotificationId

        val clickIntent = Intent(this, ProactiveCareService::class.java).apply {
            action = ACTION_NOTIFICATION_CLICKED
            putExtra(EXTRA_CARE_RECORD_ID, recordId)
            putExtra(EXTRA_CARE_CONTENT, content)
            putExtra(EXTRA_TRIGGER_TYPE, triggerType.name)
        }
        val clickPendingIntent = PendingIntent.getService(
            this, notificationId * 10 + 1, clickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dismissIntent = Intent(this, ProactiveCareService::class.java).apply {
            action = ACTION_NOTIFICATION_DISMISSED
            putExtra(EXTRA_CARE_RECORD_ID, recordId)
        }
        val dismissPendingIntent = PendingIntent.getService(
            this, notificationId * 10 + 2, dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val smallIcon = resolveIcon(
            custom = "ic_heart_notification",
            fallback = android.R.drawable.ic_menu_view
        )

        val notification = NotificationCompat.Builder(this, LingShuApp.CHANNEL_PROACTIVE)
            .setContentTitle("灵枢的关怀 · ${triggerType.displayName}")
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setSmallIcon(smallIcon)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL)
            .setContentIntent(clickPendingIntent)
            .setDeleteIntent(dismissPendingIntent)
            .build()

        notificationManager.notify(notificationId, notification)
    }

    // ==================== 用户点击 / 滑掉通知 ====================

    private fun handleNotificationClicked(intent: Intent) {
        val recordId = intent.getStringExtra(EXTRA_CARE_RECORD_ID)
        val content = intent.getStringExtra(EXTRA_CARE_CONTENT)
        val triggerTypeName = intent.getStringExtra(EXTRA_TRIGGER_TYPE)
        val triggerType: TriggerType? = runCatching {
            TriggerType.valueOf(triggerTypeName ?: "")
        }.getOrNull()

        recordId?.let { id ->
            serviceScope.launch {
                repository.updateCareFeedback(id, ProactiveTriggers.CareFeedback.INTERACTED)
            }
        }

        // 通知 UI 层启动对话
        broadcastCareTriggered(triggerType, content, recordId)

        // 同时尝试拉起应用主界面，让用户能马上聊天
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        launchIntent?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            it.putExtra(EXTRA_CARE_RECORD_ID, recordId)
            it.putExtra(EXTRA_CARE_CONTENT, content)
            runCatching { startActivity(it) }
        }
    }

    private fun handleNotificationDismissed(intent: Intent) {
        val recordId = intent.getStringExtra(EXTRA_CARE_RECORD_ID)
        recordId?.let { id ->
            serviceScope.launch {
                repository.updateCareFeedback(id, ProactiveTriggers.CareFeedback.DISMISSED)
            }
        }
    }

    // ==================== 广播通知 UI 层 ====================

    private fun broadcastCareTriggered(
        triggerType: TriggerType?,
        content: String?,
        recordId: String?
    ) {
        val broadcastIntent = Intent(ACTION_BROADCAST_CARE_TRIGGERED).apply {
            setPackage(packageName)
            triggerType?.let { putExtra(EXTRA_TRIGGER_TYPE, it.name) }
            content?.let { putExtra(EXTRA_CARE_CONTENT, it) }
            recordId?.let { putExtra(EXTRA_CARE_RECORD_ID, it) }
        }
        sendBroadcast(broadcastIntent)
    }

    // ==================== 工具方法 ====================

    /**
     * 安全地按 resName 获取字符串资源，不存在时使用 fallback 硬编码文案，
     * 避免因为 strings.xml 资源缺失导致 Service 启动崩溃。
     */
    private fun safeGetString(fallback: String, resName: String): String {
        val resId = resources.getIdentifier(resName, "string", packageName)
        return if (resId != 0) getString(resId) else fallback
    }

    /**
     * 优先使用项目内图标，不存在则使用 Android 系统内置图标兜底，
     * 避免因 R.drawable 缺失导致编译失败 / 崩溃。
     */
    private fun resolveIcon(custom: String, fallback: Int): Int {
        val resId = resources.getIdentifier(custom, "drawable", packageName)
        return if (resId != 0) resId else fallback
    }
}

// ============================================================================
// WorkManager Worker：定期巡检触发（在应用被 Doze / 省电白名单外也能跑）
// ============================================================================

/**
 * 定期检测 Worker
 * 由 WorkManager 调度，每隔 15 分钟执行一次触发检测；
 * 如果命中触发条件，会通过发送 ACTION_CHECK_NOW 的方式唤醒前台服务发送通知。
 */
@HiltWorker
class ProactiveCareCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val decisionEngine: ProactiveDecisionEngine,
    private val contentGenerator: ProactiveContentGenerator,
    private val repository: ProactiveCareRepository,
    private val config: ProactiveConfig
) : Worker(context, workerParams) {

    override fun doWork(): Result {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return try {
            val hit = kotlinx.coroutines.runBlocking {
                runCatching {
                    // 1. 前置检查
                    val (canProceed, _) = decisionEngine.preflightCheck()
                    if (!canProceed) return@runCatching false

                    // 2. 决策（默认快照）
                    val snapshot = decisionEngine.buildSnapshot()
                    val decision: TriggerResult = decisionEngine.shouldTrigger(snapshot)
                    if (!decision.shouldTrigger || decision.triggerType == null) {
                        return@runCatching false
                    }

                    // 3. 生成内容 + 记录（真正发通知交给 Service 去做，因为需要 NotificationManager）
                    val careText = runCatching {
                        contentGenerator.generateCareContent(decision, null, null)
                    }.getOrDefault("记得照顾好自己哦~")

                    repository.recordCareTriggered(
                        triggerType = decision.triggerType,
                        triggerReason = decision.reason,
                        careContent = careText,
                        triggerData = mapOf("fromWorker" to true)
                    )

                    // 规格书 P5：记录按触发类型的上次触发时间
                    repository.setLastTriggerTime(decision.triggerType)

                    // 4. 拉起 Service 去发通知（Worker 本身不应直接弹窗/发通知）
                    val serviceIntent = Intent(
                        applicationContext,
                        ProactiveCareService::class.java
                    ).apply {
                        action = ProactiveCareService.ACTION_CHECK_NOW
                    }
                    runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            applicationContext.startForegroundService(serviceIntent)
                        } else {
                            applicationContext.startService(serviceIntent)
                        }
                    }
                    true
                }.getOrDefault(false)
            }
            scope.cancel()
            if (hit) Result.success() else Result.retry()
        } catch (t: Throwable) {
            scope.cancel()
            Log.w("ProactiveCareWorker", "doWork 异常", t)
            Result.retry()
        }
    }
}
