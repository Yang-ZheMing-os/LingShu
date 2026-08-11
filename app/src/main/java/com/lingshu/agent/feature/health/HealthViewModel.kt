package com.lingshu.agent.feature.health

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lingshu.agent.core.model.HealthData
import com.lingshu.agent.feature.model.MessageRole
import com.lingshu.agent.feature.model.ModelMessage
import com.lingshu.agent.feature.model.ModelResponse
import com.lingshu.agent.feature.model.ModelRouter
import com.lingshu.agent.feature.model.ResponseStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 健康模块 ViewModel
 *
 * UI 绑定场景：
 * 1. 健康仪表盘（今日概览 + 实时数据）
 * 2. 趋势图页（7天/30天步数/心率/睡眠趋势）
 * 3. 历史数据浏览
 * 4. 健康建议页（规则模板 + AI模型生成的个性化建议）
 *
 * 数据流：
 * - HealthManager 实时采集 → HealthRepository 持久化 → ViewModel StateFlow → UI
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HealthViewModel @Inject constructor(
    private val manager: HealthManager,
    private val repository: HealthRepository,
    private val modelRouter: ModelRouter
) : ViewModel() {

    companion object {
        private const val TAG = "HealthViewModel"

        /** 建议生成模式 */
        const val MODE_RULE = "rule"       // 仅规则模板
        const val MODE_AI = "ai"           // AI 模型生成
        const val MODE_HYBRID = "hybrid"   // 规则+AI融合
    }

    // ==================== 一次性事件 ====================
    private val _event = MutableSharedFlow<HealthEvent>()
    val event: SharedFlow<HealthEvent> = _event.asSharedFlow()

    // ==================== UI 状态：基础配置 ====================

    /** 趋势图时间范围（7天/30天） */
    private val _trendRangeDays = MutableStateFlow(7)
    val trendRangeDays: StateFlow<Int> = _trendRangeDays.asStateFlow()

    /** 建议生成模式 */
    private val _adviceMode = MutableStateFlow(MODE_HYBRID)
    val adviceMode: StateFlow<String> = _adviceMode.asStateFlow()

    // ==================== UI 状态：实时数据 ====================

    /** 最新实时健康数据（来自 HealthManager，缺失字段以 mock 兜底） */
    val latestRealTime: StateFlow<HealthData> = manager.latestData
        .mapLatest { data ->
            val mock = HealthData.mock()
            data.copy(
                heartRate = data.heartRate ?: mock.heartRate,
                spo2 = data.spo2 ?: mock.spo2,
                steps = data.steps ?: mock.steps,
                calories = data.calories ?: mock.calories,
                stressLevel = data.stressLevel ?: mock.stressLevel,
                sleepTotalMinutes = data.sleepTotalMinutes ?: mock.sleepTotalMinutes,
                sleepEfficiency = data.sleepEfficiency ?: mock.sleepEfficiency,
                sleepDeepMinutes = data.sleepDeepMinutes ?: mock.sleepDeepMinutes,
                activeMinutes = data.activeMinutes ?: mock.activeMinutes,
                sleepSegments = data.sleepSegments.ifEmpty { mock.sleepSegments }
            )
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            HealthData.mock()
        )

    /** 监控是否正在运行 */
    val isMonitoring: StateFlow<Boolean> = manager.isMonitoring

    /** 异常事件（转发到UI层显示通知/弹窗） */
    val anomalyEvents: SharedFlow<HealthAnomalyEvent> = manager.anomalyEvents

    // ==================== UI 状态：今日概览 ====================

    private val _todaySummary = MutableStateFlow(DailyStats(
        startTime = repository.getTodayStart(),
        endTime = System.currentTimeMillis()
    ))
    val todaySummary: StateFlow<DailyStats> = _todaySummary.asStateFlow()

    /** 习惯评分 */
    private val _habitScore = MutableStateFlow(HabitScore(score = 0, factors = emptyMap()))
    val habitScore: StateFlow<HabitScore> = _habitScore.asStateFlow()

    // ==================== UI 状态：趋势图 ====================

    /** 步数趋势序列：Pair(日期时间戳, 步数) */
    private val _stepsTrend = MutableStateFlow<List<Pair<Long, Long>>>(emptyList())
    val stepsTrend: StateFlow<List<Pair<Long, Long>>> = _stepsTrend.asStateFlow()

    /** 心率趋势序列：Pair(日期时间戳, 平均心率) */
    private val _heartRateTrend = MutableStateFlow<List<Pair<Long, Double>>>(emptyList())
    val heartRateTrend: StateFlow<List<Pair<Long, Double>>> = _heartRateTrend.asStateFlow()

    /** 睡眠趋势序列：Pair(日期时间戳, 分钟) */
    private val _sleepTrend = MutableStateFlow<List<Pair<Long, Double>>>(emptyList())
    val sleepTrend: StateFlow<List<Pair<Long, Double>>> = _sleepTrend.asStateFlow()

    // ==================== UI 状态：习惯分析 ====================

    private val _sleepTrendAnalysis = MutableStateFlow(
        SleepTrendAnalysis(0, 0, 0, 0, 0, TrendSeverity.NONE)
    )
    val sleepTrendAnalysis: StateFlow<SleepTrendAnalysis> = _sleepTrendAnalysis.asStateFlow()

    private val _stepsTrendAnalysis = MutableStateFlow(
        StepsTrendAnalysis(0, 0, 0L, 0, 0, 0f, TrendDirection.STABLE, 0.0, TrendSeverity.NONE)
    )
    val stepsTrendAnalysis: StateFlow<StepsTrendAnalysis> = _stepsTrendAnalysis.asStateFlow()

    // ==================== UI 状态：建议 ====================

    /** 规则模板生成的建议列表 */
    private val _ruleAdvice = MutableStateFlow<List<String>>(emptyList())
    val ruleAdvice: StateFlow<List<String>> = _ruleAdvice.asStateFlow()

    /** AI 生成的建议文本 */
    private val _aiAdvice = MutableStateFlow("")
    val aiAdvice: StateFlow<String> = _aiAdvice.asStateFlow()

    /** AI 建议生成中 */
    private val _isAiAdviceLoading = MutableStateFlow(false)
    val isAiAdviceLoading: StateFlow<Boolean> = _isAiAdviceLoading.asStateFlow()

    // ==================== UI 状态：历史数据 ====================

    /** 当前查询历史的时间范围 */
    private val _historyRange = MutableStateFlow<Pair<Long, Long>?>(null)
    val historyRange: StateFlow<Pair<Long, Long>?> = _historyRange.asStateFlow()

    /** 当前查询到的历史数据列表 */
    private val _historyList = MutableStateFlow<List<HealthData>>(emptyList())
    val historyList: StateFlow<List<HealthData>> = _historyList.asStateFlow()

    /** 历史数据加载中 */
    private val _isHistoryLoading = MutableStateFlow(false)
    val isHistoryLoading: StateFlow<Boolean> = _isHistoryLoading.asStateFlow()

    // ==================== 自动采集入库 Job ====================
    private var collectRealTimeJob: Job? = null

    init {
        // 订阅 HealthManager 的实时数据，自动写入 Repository
        collectRealTimeJob = manager.realTimeData
            .onEach { data ->
                try {
                    repository.save(data)
                } catch (e: Exception) {
                    Log.e(TAG, "保存实时健康数据失败: ${e.message}", e)
                }
            }
            .launchIn(viewModelScope)

        // 启动时加载一次仪表盘数据
        refreshDashboard()
    }

    // ==================== 仪表盘操作 ====================

    /**
     * 刷新仪表盘：重新加载今日概览、习惯评分、趋势
     */
    fun refreshDashboard() {
        viewModelScope.launch {
            runCatching {
                _todaySummary.value = repository.getTodaySummary()
                _habitScore.value = repository.calculateHabitScore(7)
                _ruleAdvice.value = repository.generateRuleBasedAdvice()
                _sleepTrendAnalysis.value = repository.analyzeSleepTrend(7)
                _stepsTrendAnalysis.value = repository.analyzeStepsTrend(7)
                loadTrendSeries(_trendRangeDays.value)
            }.onFailure {
                Log.e(TAG, "刷新仪表盘失败: ${it.message}", it)
                _event.emit(HealthEvent.DashboardRefreshFailed(it.message ?: "未知错误"))
            }
        }
    }

    /**
     * 切换趋势图天数并重新加载
     */
    fun setTrendRangeDays(days: Int) {
        _trendRangeDays.value = days
        loadTrendSeries(days)
    }

    /**
     * 加载趋势序列（步数/心率/睡眠）
     */
    private fun loadTrendSeries(days: Int) {
        viewModelScope.launch {
            runCatching {
                _stepsTrend.value = repository.getDailyStepsSeries(days)
                _heartRateTrend.value = repository.getDailyHeartRateSeries(days)
                _sleepTrend.value = repository.getDailySleepSeries(days)
            }.onFailure {
                Log.e(TAG, "加载趋势数据失败: ${it.message}", it)
            }
        }
    }

    // ==================== AI 健康建议 ====================

    /**
     * 生成 AI 健康建议
     *
     * 工作流程：
     * 1. 收集最近 7 天的统计数据和异常事件上下文
     * 2. 根据 adviceMode 决定使用规则/AI/融合模式
     * 3. 通过 ModelRouter.chat 调用对话模型生成个性化建议
     */
    fun generateAiAdvice() {
        if (_isAiAdviceLoading.value) return
        _isAiAdviceLoading.value = true
        _aiAdvice.value = ""

        viewModelScope.launch {
            try {
                val context = buildHealthContext()
                val mode = _adviceMode.value

                // 先生成规则建议（所有模式都需要）
                val ruleList = repository.generateRuleBasedAdvice()
                _ruleAdvice.value = ruleList

                if (mode == MODE_RULE) {
                    _aiAdvice.value = ruleList.joinToString("\n\n") { "• $it" }
                    _isAiAdviceLoading.value = false
                    return@launch
                }

                // AI 模式：构建 Prompt
                val systemPrompt = """
你是一位专业、亲切的健康助理。根据用户提供的近期健康数据和规则建议，
给出个性化、可执行的健康建议。

要求：
1. 语气温暖、鼓励，不使用恐吓式表达
2. 建议分点列出，每点给出具体可操作的动作
3. 优先结合最近7天数据趋势（不要凭空捏造）
4. 整体字数控制在 300~500 字
5. 如发现严重异常指标，温和提醒用户考虑就医
                """.trimIndent()

                val userPrompt = """
【最近7天健康摘要】
${context.summaryText}

【规则分析发现的问题】
${ruleList.joinToString("\n") { "- $it" }}

【睡眠趋势】
连续不足天数: ${_sleepTrendAnalysis.value.consecutiveInsufficientDays} 天
平均睡眠: ${_sleepTrendAnalysis.value.averageSleepMinutes / 60}小时${_sleepTrendAnalysis.value.averageSleepMinutes % 60}分
严重程度: ${_sleepTrendAnalysis.value.severity.name}

【步数趋势】
平均步数: ${_stepsTrendAnalysis.value.averageSteps}
达标率: ${"%.1f".format(_stepsTrendAnalysis.value.goalReachRate * 100)}%
方向: ${_stepsTrendAnalysis.value.direction.name}

【综合习惯评分】
${_habitScore.value.grade}（${_habitScore.value.score}/100）
分项: ${_habitScore.value.factors.entries.joinToString { (k, v) -> "$k=$v" }}

请根据以上信息给出健康建议：
                """.trimIndent()

                val messages = listOf(
                    ModelMessage(role = MessageRole.SYSTEM, content = systemPrompt),
                    ModelMessage(role = MessageRole.USER, content = userPrompt)
                )

                // 路由到模型
                val response: ModelResponse = modelRouter.chat(messages)

                if (response.isSuccess) {
                    if (mode == MODE_HYBRID) {
                        val hybrid = buildString {
                            appendLine("【参考建议】")
                            ruleList.forEach { appendLine("• $it") }
                            appendLine()
                            appendLine("【灵枢AI个性化建议】")
                            append(response.content)
                        }
                        _aiAdvice.value = hybrid
                    } else {
                        _aiAdvice.value = response.content
                    }
                    _event.emit(HealthEvent.AiAdviceGenerated)
                } else {
                    Log.w(TAG, "AI建议生成失败: ${response.errorMessage}，降级使用规则建议")
                    _aiAdvice.value = ruleList.joinToString("\n\n") { "• $it" }
                    _event.emit(HealthEvent.AiAdviceFallback(response.errorMessage ?: "模型不可用"))
                }

            } catch (e: Exception) {
                Log.e(TAG, "AI建议异常: ${e.message}", e)
                _aiAdvice.value = _ruleAdvice.value.joinToString("\n\n") { "• $it" }
                _event.emit(HealthEvent.AiAdviceFallback(e.message ?: "未知异常"))
            } finally {
                _isAiAdviceLoading.value = false
            }
        }
    }

    /**
     * 构建近期健康数据摘要文本（喂给Prompt用）
     */
    private suspend fun buildHealthContext(): HealthContext {
        val weekStats = repository.getDailyStatsForDays(7)
        val summaryText = buildString {
            val avgSteps = weekStats.map { it.totalSteps }.average().toLong()
            val avgSleep = weekStats.map { it.sleepMinutes }.average().toInt()
            val avgHr = weekStats.map { it.avgHeartRate }.average()
            val avgSpo2 = weekStats.map { it.avgSpo2 }.average()
            val avgStress = weekStats.map { it.avgStress }.average()

            append("- 平均每日步数: $avgSteps (目标${HealthRepository.DAILY_TARGET_STEPS})\n")
            append("- 平均睡眠: ${avgSleep / 60}小时${avgSleep % 60}分 (目标7小时)\n")
            if (avgHr > 0) append("- 平均心率: ${"%.1f".format(avgHr)} BPM\n")
            if (avgSpo2 > 0) append("- 平均血氧: ${"%.1f".format(avgSpo2)}%\n")
            if (avgStress > 0) append("- 平均压力指数: ${"%.1f".format(avgStress)}/100\n")

            appendLine("\n【每日明细】")
            weekStats.forEach { stat ->
                appendLine("${stat.dateLabel}: 步数=${stat.totalSteps}, 睡眠=${stat.sleepMinutes}分, 心率=${stat.avgHeartRate.toInt()}")
            }
        }
        return HealthContext(summaryText = summaryText, dailyStats = weekStats)
    }

    /** 设置建议模式 */
    fun setAdviceMode(mode: String) {
        if (mode in listOf(MODE_RULE, MODE_AI, MODE_HYBRID)) {
            _adviceMode.value = mode
        }
    }

    // ==================== 历史查询 ====================

    /**
     * 查询历史数据
     * @param startTime 开始时间戳
     * @param endTime 结束时间戳
     * @param dataType 可选的数据类型过滤
     */
    fun queryHistory(
        startTime: Long,
        endTime: Long,
        dataType: String? = null
    ) {
        _historyRange.value = startTime to endTime
        _isHistoryLoading.value = true

        viewModelScope.launch {
            runCatching {
                // 先查本地 Room
                val local = if (dataType != null) {
                    repository.getByTypeAndTime(dataType, startTime, endTime)
                } else {
                    repository.getByTimeRange(startTime, endTime)
                }

                // 再回源 Health Connect（按需，数据不足时）
                val hc = if (local.size < 24) {
                    manager.queryHistory(startTime, endTime, dataType)
                } else emptyList()

                // 合并去重（按 id）
                val merged = (local + hc).distinctBy { it.id to it.timestamp }
                    .sortedBy { it.timestamp }

                // 回源到的 Health Connect 数据也写入库
                if (hc.isNotEmpty()) {
                    repository.saveAll(hc)
                }

                _historyList.value = merged
            }.onFailure {
                Log.e(TAG, "查询历史失败: ${it.message}", it)
                _event.emit(HealthEvent.HistoryQueryFailed(it.message ?: "查询失败"))
            }
            _isHistoryLoading.value = false
        }
    }

    // ==================== 监控控制 ====================

    /** 启动健康监控 */
    fun startMonitoring() {
        manager.startMonitoring()
        viewModelScope.launch {
            _event.emit(HealthEvent.MonitoringStarted)
        }
    }

    /** 停止健康监控 */
    fun stopMonitoring() {
        manager.stopMonitoring()
        viewModelScope.launch {
            _event.emit(HealthEvent.MonitoringStopped)
        }
    }

    /** 切换监控状态 */
    fun toggleMonitoring() {
        if (isMonitoring.value) stopMonitoring() else startMonitoring()
    }

    override fun onCleared() {
        super.onCleared()
        collectRealTimeJob?.cancel()
    }
}

// ==================== UI 事件 & 上下文 ====================

/**
 * 一次性 UI 事件
 */
sealed class HealthEvent {
    object MonitoringStarted : HealthEvent()
    object MonitoringStopped : HealthEvent()
    object AiAdviceGenerated : HealthEvent()
    data class AiAdviceFallback(val reason: String) : HealthEvent()
    data class DashboardRefreshFailed(val reason: String) : HealthEvent()
    data class HistoryQueryFailed(val reason: String) : HealthEvent()
}

/**
 * 传给 Prompt 的健康上下文
 */
data class HealthContext(
    val summaryText: String,
    val dailyStats: List<DailyStats>
)
