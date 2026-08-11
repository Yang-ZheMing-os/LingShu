package com.lingshu.agent.feature.proactive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lingshu.agent.core.model.Persona
import com.lingshu.agent.core.model.routing.ModelType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

/**
 * 主动关怀 ViewModel 层
 *
 * 为主动关怀设置页面和历史记录页面提供数据和操作能力：
 * 1. 展示配置状态（总开关、冷却时间、每日上限、模型选择、各触发条件启用状态等）
 * 2. 展示关怀历史记录和统计数据
 * 3. 展示运行状态（今日已触发次数、上次触发时间、剩余冷却时间等）
 * 4. 提供修改配置的操作方法
 * 5. 提供手动测试触发和生成内容的能力（用于调试）
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProactiveCareViewModel @Inject constructor(
    private val config: ProactiveConfig,
    private val repository: ProactiveCareRepository,
    private val decisionEngine: ProactiveDecisionEngine,
    private val contentGenerator: ProactiveContentGenerator
) : ViewModel() {

    // ==================== UI State Flow ====================

    /**
     * 主动关怀主页面UI状态
     * 组合配置流 + Repository统计流
     */
    val uiState: StateFlow<ProactiveCareUiState> = config.configFlow
        .combine(repository.todayCareCountFlow) { configState, todayCount ->
            configState to todayCount
        }
        .combine(repository.lastCareTimestampFlow) { (configState, todayCount), lastCareTs ->
            Triple(configState, todayCount, lastCareTs)
        }
        .combine(repository.lastCareTriggerTypeFlow) { (configState, todayCount, lastCareTs), lastCareType ->
            Quad(configState, todayCount, lastCareTs, lastCareType)
        }
        .combine(repository.careHistoryFlow) { (configState, todayCount, lastCareTs, lastCareType), history ->
            val now = System.currentTimeMillis()
            val remainingCooldownMs = lastCareTs?.let {
                (configState.cooldownMs - (now - it)).coerceAtLeast(0L)
            } ?: 0L

            ProactiveCareUiState(
                config = configState,
                todayCareCount = todayCount,
                dailyLimitRemaining = (configState.dailyLimit - todayCount).coerceAtLeast(0),
                lastCareTimestamp = lastCareTs,
                lastCareTriggerType = lastCareType,
                remainingCooldownMs = remainingCooldownMs,
                isInCooldown = remainingCooldownMs > 0,
                recentHistory = history.take(20)
            )
        }
        .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ProactiveCareUiState()
    )

    /**
     * 历史记录页面UI状态
     * 独立的Flow，避免主页面加载全部历史数据
     */
    val historyUiState: StateFlow<ProactiveCareHistoryUiState> = combine(
        repository.careHistoryFlow,
        config.dailyLimit
    ) { history, dailyLimit ->
        // 计算各类型的统计
        val typeStats = history
            .groupBy { it.triggerType }
            .mapValues { it.value.size }
            .toSortedMap(compareBy { it?.name ?: "" })

        ProactiveCareHistoryUiState(
            allHistory = history,
            totalCount = history.size,
            triggerTypeStats = typeStats,
            todayCount = repository.todayCareCountFlow.value,
            dailyLimit = dailyLimit
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ProactiveCareHistoryUiState()
    )

    /**
     * 单次触发测试的状态流
     * 用于"立即测试"功能
     */
    private val _testResult = kotlinx.coroutines.flow.MutableStateFlow<CareTestResult?>(null)
    val testResult: StateFlow<CareTestResult?> = _testResult

    // ==================== 配置修改操作 ====================

    /** 设置总开关 */
    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            config.setEnabled(enabled)
        }
    }

    /** 设置冷却时间（分钟） */
    fun setCooldownMinutes(minutes: Int) {
        viewModelScope.launch {
            config.setCooldownMinutes(minutes)
        }
    }

    /** 设置每日关怀上限 */
    fun setDailyLimit(limit: Int) {
        viewModelScope.launch {
            config.setDailyLimit(limit)
        }
    }

    /** 设置内容生成模型 */
    fun setGenerationModel(modelType: ModelType) {
        viewModelScope.launch {
            config.setGeneratorModelType(modelType.name)
        }
    }

    /** 设置内容生成策略 */
    fun setGenerationStrategy(strategy: GenerationStrategy) {
        viewModelScope.launch {
            config.setGenerationStrategy(strategy)
        }
    }

    /** 切换指定触发条件的启用状态 */
    fun toggleTrigger(triggerType: TriggerType, enabled: Boolean) {
        viewModelScope.launch {
            config.setTriggerEnabled(triggerType, enabled)
        }
    }

    /** 设置是否自动启动对话 */
    fun setAutoLaunchChat(enabled: Boolean) {
        viewModelScope.launch {
            config.setAutoLaunchChat(enabled)
        }
    }

    /** 添加自定义提醒时间 */
    fun addCustomReminder(reminder: CustomReminder) {
        viewModelScope.launch {
            config.addCustomReminder(reminder)
        }
    }

    /** 移除自定义提醒时间 */
    fun removeCustomReminder(reminderId: String) {
        viewModelScope.launch {
            config.removeCustomReminder(reminderId)
        }
    }

    /** 更新自定义提醒时间列表 */
    fun updateCustomReminders(reminders: List<CustomReminder>) {
        viewModelScope.launch {
            config.setCustomReminders(reminders)
        }
    }

    /** 添加纪念日 */
    fun addMemorialDay(day: MemoryEvent) {
        viewModelScope.launch {
            config.addMemorialDay(day)
        }
    }

    /** 移除纪念日 */
    fun removeMemorialDay(dayId: String) {
        viewModelScope.launch {
            config.removeMemorialDay(dayId)
        }
    }

    /** 更新纪念日列表 */
    fun updateMemorialDays(days: List<MemoryEvent>) {
        viewModelScope.launch {
            config.setMemorialDays(days)
        }
    }

    /** 批量设置启用的触发条件 */
    fun setEnabledTriggers(triggers: Set<TriggerType>) {
        viewModelScope.launch {
            config.setEnabledTriggerTypes(triggers)
        }
    }

    /** 重置为默认配置 */
    fun resetConfig() {
        viewModelScope.launch {
            config.resetToDefault()
        }
    }

    // ==================== 阈值调整（规格书 P5） ====================

    /** 设置久坐阈值（分钟） */
    fun setSedentaryThreshold(minutes: Int) {
        viewModelScope.launch {
            config.setSedentaryThresholdHours((minutes / 60).coerceIn(1, 4))
        }
    }

    /** 设置心率下限 */
    fun setHeartRateLower(bpm: Int) {
        viewModelScope.launch { config.setHeartRateLower(bpm) }
    }

    /** 设置心率上限 */
    fun setHeartRateUpper(bpm: Int) {
        viewModelScope.launch { config.setHeartRateUpper(bpm) }
    }

    /** 设置压力指数阈值（整数 30-95） */
    fun setStressIndexThreshold(value: Int) {
        viewModelScope.launch { config.setStressIndexThreshold(value) }
    }

    /** 设置降水概率阈值 */
    fun setRainProbabilityThreshold(prob: Int) {
        viewModelScope.launch { config.setRainProbabilityThreshold(prob) }
    }

    /** 设置暗光 lux 阈值 */
    fun setLowLightLuxThreshold(lux: Int) {
        viewModelScope.launch { config.setLowLightLuxThreshold(lux) }
    }

    // ==================== 勿扰时段（规格书 4.2） ====================

    fun setQuietHoursEnabled(enabled: Boolean) {
        viewModelScope.launch { config.setQuietHoursEnabled(enabled) }
    }

    fun setQuietHoursStart(hour: Int) {
        viewModelScope.launch { config.setQuietHoursStart(hour.coerceIn(0, 23)) }
    }

    fun setQuietHoursEnd(hour: Int) {
        viewModelScope.launch { config.setQuietHoursEnd(hour.coerceIn(0, 23)) }
    }

    // ==================== 记录操作 ====================

    /** 更新用户反馈 */
    fun updateFeedback(recordId: String, feedback: ProactiveTriggers.CareFeedback) {
        viewModelScope.launch {
            repository.updateCareFeedback(recordId, feedback)
        }
    }

    /** 清除所有历史记录 */
    fun clearHistory() {
        viewModelScope.launch {
            repository.clearCareHistory()
        }
    }

    /** 记录负面情绪（用于测试跟进功能） */
    fun recordNegativeMoodForTest(description: String? = null) {
        viewModelScope.launch {
            repository.recordNegativeMood(description)
        }
    }

    // ==================== 测试/调试功能 ====================

    /**
     * 手动执行一次触发检测（用于"立即测试"按钮）
     * 忽略冷却时间和每日上限的限制，纯粹测试触发条件和内容生成
     */
    fun runTriggerTest(
        persona: Persona? = null,
        sensorSnapshot: ProactiveTriggers.SensorSnapshot = ProactiveTriggers.SensorSnapshot(),
        behaviorSnapshot: ProactiveTriggers.BehaviorSnapshot = ProactiveTriggers.BehaviorSnapshot(),
        memorySnapshot: ProactiveTriggers.MemorySnapshot? = null
    ) {
        viewModelScope.launch {
            _testResult.value = CareTestResult(
                status = TestStatus.RUNNING,
                message = "正在检测触发条件..."
            )

            try {
                // 加载配置
                val configState = config.getConfig()
                val now = Calendar.getInstance()

                // 构建 TriggerSnapshot（从快照映射到决策引擎参数）
                val snapshot = TriggerSnapshot(
                    currentHour = now.get(Calendar.HOUR_OF_DAY),
                    currentMinute = now.get(Calendar.MINUTE),
                    currentDayOfWeek = now.get(Calendar.DAY_OF_WEEK),
                    isScreenOn = true,
                    unlockCountSinceMorning = behaviorSnapshot.unlockTimestamps.size,
                    lastUnlockTime = behaviorSnapshot.unlockTimestamps.lastOrNull() ?: 0L,
                    currentForegroundApp = behaviorSnapshot.currentForegroundApp,
                    foregroundAppStartTime = System.currentTimeMillis() - behaviorSnapshot.currentAppDurationMs,
                    sedentaryMinutes = (sensorSnapshot.sedentaryDurationMs / 60000).toInt(),
                    currentHeartRate = sensorSnapshot.currentHeartRate,
                    heartRateThresholdMin = 50,
                    heartRateThresholdMax = 100,
                    stillMinutes = (sensorSnapshot.stationaryDurationMs / 60000).toInt(),
                    upcomingBirthdays = configState.memorialDays
                        .filter { it.type == "birthday" }.map { it.title },
                    upcomingAnniversaries = configState.memorialDays
                        .filter { it.type == "anniversary" }.map { it.title },
                    lastNegativeMoodDays = 0,
                    lastCareTime = 0L,
                    todayCareCount = 0,
                    totalDaysWithApp = 1
                )

                // 1. 运行决策引擎
                val result = decisionEngine.shouldTrigger(snapshot)

                if (!result.shouldTrigger) {
                    _testResult.value = CareTestResult(
                        status = TestStatus.NO_TRIGGER,
                        message = result.reason,
                        decision = result
                    )
                    return@launch
                }

                // 2. 生成内容
                _testResult.value = CareTestResult(
                    status = TestStatus.GENERATING,
                    message = "正在生成关怀内容...",
                    decision = result
                )

                val generatedContent = contentGenerator.generateCareContent(
                    trigger = result,
                    persona = persona
                )

                _testResult.value = CareTestResult(
                    status = TestStatus.SUCCESS,
                    message = "测试成功！触发类型：${result.triggerType?.displayName}",
                    decision = result,
                    generatedContent = generatedContent
                )
            } catch (e: Exception) {
                _testResult.value = CareTestResult(
                    status = TestStatus.ERROR,
                    message = "测试失败：${e.message}",
                    error = e
                )
            }
        }
    }

    /** 清除测试结果 */
    fun clearTestResult() {
        _testResult.value = null
    }
}

// ==================== UI State 数据类 ====================

/**
 * 主动关怀主页面UI状态
 */
data class ProactiveCareUiState(
    val config: ProactiveConfigState = ProactiveConfigState(),
    val todayCareCount: Int = 0,
    val dailyLimitRemaining: Int = 0,
    val lastCareTimestamp: Long? = null,
    val lastCareTriggerType: TriggerType? = null,
    val remainingCooldownMs: Long = 0L,
    val isInCooldown: Boolean = false,
    val recentHistory: List<CareRecordEntry> = emptyList()
) {
    /** 总开关是否启用 */
    val isEnabled: Boolean get() = config.enabled

    /** 冷却时间剩余分钟（UI展示用） */
    val remainingCooldownMinutes: Int get() = (remainingCooldownMs / 60000).toInt()

    /** 上次触发时间的可读描述 */
    val lastCareDescription: String
        get() {
            val ts = lastCareTimestamp ?: return "尚未触发过关怀"
            val minutesAgo = (System.currentTimeMillis() - ts) / 60000
            val typeName = lastCareTriggerType?.displayName ?: "关怀"
            return when {
                minutesAgo < 1 -> "刚刚触发了「$typeName」"
                minutesAgo < 60 -> "${minutesAgo}分钟前触发了「$typeName」"
                minutesAgo < 60 * 24 -> "${minutesAgo / 60}小时前触发了「$typeName」"
                else -> "${minutesAgo / (60 * 24)}天前触发了「$typeName」"
            }
        }
}

/**
 * 历史记录页面UI状态
 */
data class ProactiveCareHistoryUiState(
    val allHistory: List<CareRecordEntry> = emptyList(),
    val totalCount: Int = 0,
    val triggerTypeStats: Map<TriggerTypeCategory?, Int> = emptyMap(),
    val todayCount: Int = 0,
    val dailyLimit: Int = 5
) {
    /** 今日使用百分比 */
    val todayUsagePercent: Float
        get() = if (dailyLimit > 0) todayCount.toFloat() / dailyLimit else 0f
}

/**
 * 触发测试结果
 */
data class CareTestResult(
    val status: TestStatus,
    val message: String,
    val decision: TriggerResult? = null,
    val generatedContent: String? = null,
    val error: Exception? = null
)

/**
 * 测试状态枚举
 */
enum class TestStatus(val displayName: String) {
    RUNNING("检测中"),
    NO_TRIGGER("未触发"),
    GENERATING("生成中"),
    SUCCESS("成功"),
    ERROR("错误")
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
