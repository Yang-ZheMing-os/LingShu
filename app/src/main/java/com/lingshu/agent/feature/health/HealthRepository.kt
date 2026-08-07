package com.lingshu.agent.feature.health

import android.util.Log
import com.lingshu.agent.core.database.Converters
import com.lingshu.agent.core.database.dao.HealthDataDao
import com.lingshu.agent.core.database.dao.DailyHeartRateAgg
import com.lingshu.agent.core.database.dao.DailySleepAgg
import com.lingshu.agent.core.database.dao.DailyStepsAgg
import com.lingshu.agent.core.database.entity.HealthDataEntity
import com.lingshu.agent.core.model.HealthData
import com.lingshu.agent.core.model.SleepSegment
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 健康数据仓库
 *
 * 职责：
 * 1. 健康数据持久化（Room CRUD）
 * 2. Entity <-> Model 双向转换
 * 3. 统计聚合：每日/每周/每月多维统计（步数、心率、睡眠、压力等）
 * 4. 习惯分析算法：睡眠不足趋势、步数趋势、HRV压力趋势等
 * 5. 响应式 Flow：观察最新数据、时间范围数据等
 *
 * 与 HealthManager 的关系：
 * - HealthManager 负责「采集/接入/异常检测」（数据上游）
 * - HealthRepository 负责「存储/查询/聚合分析」（数据下游）
 * - 通过 HealthManager.realTimeData.collect 自动写入 Room
 */
@Singleton
class HealthRepository @Inject constructor(
    private val healthDataDao: HealthDataDao
) {

    companion object {
        private const val TAG = "HealthRepository"

        /** 每日目标步数 */
        const val DAILY_TARGET_STEPS = 10000

        /** 每日推荐睡眠时长（分钟） */
        const val DAILY_RECOMMENDED_SLEEP_MIN = 7 * 60

        /** 睡眠不足判定阈值：低于推荐值的 85% 视为不足 */
        const val SLEEP_INSUFFICIENT_RATIO = 0.85f

        /** 数据保留天数（超过自动清理） */
        const val DATA_RETENTION_DAYS = 90
    }

    private val converters = Converters()

    // ==================== Entity <-> Model 转换 ====================

    /**
     * HealthData 模型转 HealthDataEntity
     * 推断 dataType：根据非空字段判断数据属于哪一类
     */
    fun HealthData.toEntity(): HealthDataEntity {
        val inferredType = inferDataType(this)
        val now = System.currentTimeMillis()
        return HealthDataEntity(
            id = id,
            timestamp = timestamp,
            source = source,
            dataType = note ?: inferredType,
            heartRate = heartRate,
            heartRateMin = heartRateMin,
            heartRateMax = heartRateMax,
            restingHeartRate = restingHeartRate,
            steps = steps,
            calories = calories,
            activeMinutes = activeMinutes,
            distanceMeters = distanceMeters,
            floors = floors,
            sleepSegments = converters.fromSleepSegmentList(sleepSegments),
            sleepTotalMinutes = sleepTotalMinutes,
            sleepDeepMinutes = sleepDeepMinutes,
            sleepLightMinutes = sleepLightMinutes,
            sleepRemMinutes = sleepRemMinutes,
            sleepAwakeMinutes = sleepAwakeMinutes,
            sleepEfficiency = sleepEfficiency,
            spo2 = spo2,
            spo2Min = spo2Min,
            spo2Average = spo2Average,
            stressLevel = stressLevel,
            bodyBattery = bodyBattery,
            hrvRmssd = hrvRmssd,
            hrvSdnn = hrvSdnn,
            systolicPressure = systolicPressure,
            diastolicPressure = diastolicPressure,
            temperature = temperature,
            respiratoryRate = respiratoryRate,
            note = note,
            createdAt = now,
            updatedAt = now
        )
    }

    /**
     * HealthDataEntity 转 HealthData 模型
     */
    fun HealthDataEntity.toModel(): HealthData {
        return HealthData(
            id = id,
            timestamp = timestamp,
            source = source,
            heartRate = heartRate,
            heartRateMin = heartRateMin,
            heartRateMax = heartRateMax,
            restingHeartRate = restingHeartRate,
            steps = steps,
            calories = calories,
            activeMinutes = activeMinutes,
            distanceMeters = distanceMeters,
            floors = floors,
            sleepSegments = converters.toSleepSegmentList(sleepSegments),
            sleepTotalMinutes = sleepTotalMinutes,
            sleepDeepMinutes = sleepDeepMinutes,
            sleepLightMinutes = sleepLightMinutes,
            sleepRemMinutes = sleepRemMinutes,
            sleepAwakeMinutes = sleepAwakeMinutes,
            sleepEfficiency = sleepEfficiency,
            spo2 = spo2,
            spo2Min = spo2Min,
            spo2Average = spo2Average,
            stressLevel = stressLevel,
            bodyBattery = bodyBattery,
            hrvRmssd = hrvRmssd,
            hrvSdnn = hrvSdnn,
            systolicPressure = systolicPressure,
            diastolicPressure = diastolicPressure,
            temperature = temperature,
            respiratoryRate = respiratoryRate,
            note = note
        )
    }

    /**
     * 根据数据字段推断 dataType
     */
    private fun inferDataType(data: HealthData): String = when {
        data.hasSleepData() -> HealthDataEntity.TYPE_SLEEP
        data.hasSpo2Data() -> HealthDataEntity.TYPE_SPO2
        data.steps != null || data.calories != null || data.distanceMeters != null -> HealthDataEntity.TYPE_STEPS
        data.stressLevel != null || data.hrvRmssd != null -> HealthDataEntity.TYPE_STRESS
        data.hasHeartRateData() || data.bloodPressureValid() -> HealthDataEntity.TYPE_HEART_RATE
        else -> HealthDataEntity.TYPE_VITALS
    }

    private fun HealthData.bloodPressureValid(): Boolean {
        return systolicPressure != null && diastolicPressure != null
    }

    // ==================== 写入操作 ====================

    /**
     * 保存一条健康数据
     */
    suspend fun save(data: HealthData) {
        healthDataDao.insert(data.toEntity())
    }

    /**
     * 批量保存健康数据
     */
    suspend fun saveAll(dataList: List<HealthData>) {
        if (dataList.isEmpty()) return
        healthDataDao.insertAll(dataList.map { it.toEntity() })
    }

    /**
     * 定期清理过期数据（保留 DATA_RETENTION_DAYS 天）
     * 建议每日凌晨调用一次
     */
    suspend fun cleanupExpiredData() {
        val cutoffTime = System.currentTimeMillis() - DATA_RETENTION_DAYS * 24 * 60 * 60 * 1000L
        val deletedCount = runCatching {
            healthDataDao.deleteOlderThan(cutoffTime)
            // TODO: Room deleteOlderThan 返回 void，无法直接统计删除数
        }
        Log.d(TAG, "清理过期健康数据完成（保留${DATA_RETENTION_DAYS}天）")
    }

    // ==================== 响应式查询（Flow） ====================

    /** 观察最新一条健康数据 */
    fun observeLatest(): Flow<HealthData?> {
        return healthDataDao.observeLatest().map { it?.toModel() }
    }

    /** 观察指定类型的最新一条 */
    fun observeLatestByType(dataType: String): Flow<HealthData?> {
        return healthDataDao.observeLatestByType(dataType).map { it?.toModel() }
    }

    /** 按时间范围观察 */
    fun observeByTimeRange(startTime: Long, endTime: Long): Flow<List<HealthData>> {
        return healthDataDao.observeByTimeRange(startTime, endTime)
            .map { list -> list.map { it.toModel() } }
    }

    /** 按类型+时间观察 */
    fun observeByTypeAndTime(
        dataType: String,
        startTime: Long,
        endTime: Long
    ): Flow<List<HealthData>> {
        return healthDataDao.observeByTypeAndTimeRange(dataType, startTime, endTime)
            .map { list -> list.map { it.toModel() } }
    }

    // ==================== 一次性查询 ====================

    suspend fun getById(id: String): HealthData? =
        healthDataDao.getById(id)?.toModel()

    suspend fun getByTimeRange(startTime: Long, endTime: Long): List<HealthData> =
        healthDataDao.getByTimeRange(startTime, endTime).map { it.toModel() }

    suspend fun getByTypeAndTime(
        dataType: String,
        startTime: Long,
        endTime: Long
    ): List<HealthData> =
        healthDataDao.getByTypeAndTimeRange(dataType, startTime, endTime).map { it.toModel() }

    suspend fun getRecent(limit: Int): List<HealthData> =
        healthDataDao.getRecent(limit).map { it.toModel() }

    // ==================== 时间范围工具 ====================

    /** 获取今天 00:00 的时间戳 */
    fun getTodayStart(): Long {
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    /** 获取 N 天前的开始时间戳 */
    fun getDaysAgoStart(days: Int): Long {
        return getTodayStart() - days * 24 * 60 * 60 * 1000L
    }

    // ==================== 统计聚合（每日/每周/每月） ====================

    /**
     * 今日概览统计
     */
    suspend fun getTodaySummary(): DailyStats {
        val start = getTodayStart()
        val end = System.currentTimeMillis()
        return buildDailyStats(start, end)
    }

    /**
     * 构建单日统计
     */
    private suspend fun buildDailyStats(startTime: Long, endTime: Long): DailyStats {
        return DailyStats(
            startTime = startTime,
            endTime = endTime,
            totalSteps = healthDataDao.sumSteps(startTime, endTime),
            totalCalories = healthDataDao.sumCalories(startTime, endTime),
            totalActiveMinutes = healthDataDao.sumActiveMinutes(startTime, endTime).toInt(),
            avgHeartRate = healthDataDao.avgHeartRate(startTime, endTime),
            maxHeartRate = healthDataDao.maxHeartRate(startTime, endTime),
            minHeartRate = healthDataDao.minHeartRate(startTime, endTime),
            avgRestingHr = healthDataDao.avgRestingHeartRate(startTime, endTime),
            avgSpo2 = healthDataDao.avgSpo2(startTime, endTime),
            minSpo2 = healthDataDao.minSpo2(startTime, endTime),
            avgStress = healthDataDao.avgStress(startTime, endTime),
            sleepMinutes = healthDataDao.avgSleepMinutes(startTime, endTime).toInt(),
            deepSleepMinutes = healthDataDao.avgDeepSleepMinutes(startTime, endTime).toInt(),
            sleepEfficiency = healthDataDao.avgSleepEfficiency(startTime, endTime),
            avgHrvRmssd = healthDataDao.avgHrvRmssd(startTime, endTime)
        )
    }

    /**
     * 获取近 N 天的每日统计列表（用于趋势图）
     * @param days 天数，如 7 表示最近一周（含今天）
     */
    suspend fun getDailyStatsForDays(days: Int): List<DailyStats> {
        val results = mutableListOf<DailyStats>()
        for (i in days - 1 downTo 0) {
            val dayStart = getDaysAgoStart(i)
            val dayEnd = dayStart + 24 * 60 * 60 * 1000L - 1
            results += buildDailyStats(dayStart, minOf(dayEnd, System.currentTimeMillis()))
        }
        return results
    }

    /**
     * 按日步数序列（用于绘制步数趋势图）
     */
    suspend fun getDailyStepsSeries(days: Int): List<Pair<Long, Long>> {
        val start = getDaysAgoStart(days - 1)
        val end = System.currentTimeMillis()
        val aggList = healthDataDao.dailySteps(start, end)
        // 补齐缺失日期（某一天没数据也显示0）
        val aggMap = aggList.associate { it.dayStart to it.totalSteps }
        val result = mutableListOf<Pair<Long, Long>>()
        for (i in 0 until days) {
            val dayStart = getDaysAgoStart(days - 1 - i)
            result += (dayStart to (aggMap[dayStart] ?: 0L))
        }
        return result
    }

    /**
     * 按日平均心率序列
     */
    suspend fun getDailyHeartRateSeries(days: Int): List<Pair<Long, Double>> {
        val start = getDaysAgoStart(days - 1)
        val end = System.currentTimeMillis()
        val aggList = healthDataDao.dailyAvgHeartRate(start, end)
        val aggMap = aggList.associate { it.dayStart to it.avgHr }
        val result = mutableListOf<Pair<Long, Double>>()
        for (i in 0 until days) {
            val dayStart = getDaysAgoStart(days - 1 - i)
            result += (dayStart to (aggMap[dayStart] ?: 0.0))
        }
        return result
    }

    /**
     * 按日睡眠时长序列
     */
    suspend fun getDailySleepSeries(days: Int): List<Pair<Long, Double>> {
        val start = getDaysAgoStart(days - 1)
        val end = System.currentTimeMillis()
        val aggList = healthDataDao.dailySleepMinutes(start, end)
        val aggMap = aggList.associate { it.dayStart to it.sleepMin }
        val result = mutableListOf<Pair<Long, Double>>()
        for (i in 0 until days) {
            val dayStart = getDaysAgoStart(days - 1 - i)
            result += (dayStart to (aggMap[dayStart] ?: 0.0))
        }
        return result
    }

    // ==================== 习惯分析算法 ====================

    /**
     * 睡眠不足趋势分析
     *
     * 算法逻辑：
     * 1. 统计最近 N 天（默认7天）的睡眠不足天数
     * 2. 不足判定：sleepMinutes < 推荐时长 * SLEEP_INSUFFICIENT_RATIO
     * 3. 计算连续不足天数（从今天往前数，直到遇到达标日为止）
     * 4. 生成趋势等级（无/轻微/中等/严重）
     */
    suspend fun analyzeSleepTrend(days: Int = 7): SleepTrendAnalysis {
        val daily = getDailyStatsForDays(days)
        val insufficientDays = daily.filter {
            it.sleepMinutes > 0 &&
                    it.sleepMinutes < (DAILY_RECOMMENDED_SLEEP_MIN * SLEEP_INSUFFICIENT_RATIO)
        }.size

        // 连续不足天数（从最近一天往前数）
        var consecutiveDays = 0
        for (stat in daily.asReversed()) {
            if (stat.sleepMinutes == 0) continue // 没有数据的日期跳过
            if (stat.sleepMinutes < DAILY_RECOMMENDED_SLEEP_MIN * SLEEP_INSUFFICIENT_RATIO) {
                consecutiveDays++
            } else {
                break
            }
        }

        val avgSleep = daily.map { it.sleepMinutes }.average().toInt()
        val severity = when {
            consecutiveDays >= 5 || insufficientDays >= days * 0.8f -> TrendSeverity.SEVERE
            consecutiveDays >= 3 || insufficientDays >= days * 0.5f -> TrendSeverity.MEDIUM
            insufficientDays >= days * 0.2f -> TrendSeverity.MILD
            else -> TrendSeverity.NONE
        }

        return SleepTrendAnalysis(
            totalDays = days,
            insufficientDays = insufficientDays,
            consecutiveInsufficientDays = consecutiveDays,
            averageSleepMinutes = avgSleep,
            recommendedMinutes = DAILY_RECOMMENDED_SLEEP_MIN,
            severity = severity
        )
    }

    /**
     * 步数趋势分析
     *
     * 算法逻辑：
     * 1. 统计最近 N 天达标率（>= DAILY_TARGET_STEPS 视为达标）
     * 2. 计算平均每日步数
     * 3. 线性回归估算步数斜率（递增/递减）
     */
    suspend fun analyzeStepsTrend(days: Int = 7): StepsTrendAnalysis {
        val series = getDailyStepsSeries(days)
        val stepsList = series.map { it.second }.filter { it > 0 }
        val reachedGoalCount = stepsList.count { it >= DAILY_TARGET_STEPS }
        val validDays = stepsList.size.coerceAtLeast(1)
        val avgSteps = stepsList.average().toLong()
        val goalReachRate = reachedGoalCount.toFloat() / validDays

        // 线性回归斜率（x=天序号, y=步数），正=上升，负=下降
        val slope = if (validDays >= 3) calculateSlope(stepsList) else 0.0
        val direction = when {
            slope > 500 -> TrendDirection.IMPROVING
            slope < -500 -> TrendDirection.DECLINING
            else -> TrendDirection.STABLE
        }

        val severity = when {
            goalReachRate < 0.2f -> TrendSeverity.SEVERE
            goalReachRate < 0.4f -> TrendSeverity.MEDIUM
            goalReachRate < 0.7f -> TrendSeverity.MILD
            else -> TrendSeverity.NONE
        }

        return StepsTrendAnalysis(
            totalDays = days,
            validDays = validDays,
            averageSteps = avgSteps,
            targetSteps = DAILY_TARGET_STEPS,
            goalReachDays = reachedGoalCount,
            goalReachRate = goalReachRate,
            direction = direction,
            slope = slope,
            severity = if (direction == TrendDirection.DECLINING && severity != TrendSeverity.NONE) severity else TrendSeverity.NONE
        )
    }

    /**
     * 综合健康习惯评分（0-100）
     * 加权维度：步数达成、睡眠时长、静息心率正常、血氧正常
     */
    suspend fun calculateHabitScore(days: Int = 7): HabitScore {
        val stats = getDailyStatsForDays(days)
        val valid = stats.filter { it.totalSteps > 0 || it.sleepMinutes > 0 }
        if (valid.isEmpty()) return HabitScore(score = 0, factors = emptyMap())

        // 1. 步数维度：均值相对目标（权重 35%）
        val avgSteps = valid.map { it.totalSteps }.average().coerceAtLeast(0.0)
        val stepsScore = (avgSteps / DAILY_TARGET_STEPS).coerceIn(0.0, 1.0) * 35

        // 2. 睡眠维度：均值相对推荐（权重 35%）
        val avgSleep = valid.map { it.sleepMinutes }.average().coerceAtLeast(0.0)
        val sleepScore = (avgSleep / DAILY_RECOMMENDED_SLEEP_MIN).coerceIn(0.0, 1.0) * 35

        // 3. 心率维度：静息心率是否在正常区间 60-80（权重 15%）
        val avgRhr = valid.map { it.avgRestingHr }.filter { it > 0 }.average().let { if (it.isNaN()) 0.0 else it }
        val hrScore = if (avgRhr in 60.0..80.0) {
            15.0
        } else if (avgRhr > 0) {
            val distance = minOf(abs(avgRhr - 60), abs(avgRhr - 80))
            (1 - (distance / 40)).coerceIn(0.0, 1.0) * 15
        } else 0.0

        // 4. 血氧维度：均值是否 >= 95%（权重 15%）
        val avgSpo2 = valid.map { it.avgSpo2 }.filter { it > 0 }.average().let { if (it.isNaN()) 0.0 else it }
        val spo2Score = if (avgSpo2 >= 95) {
            15.0
        } else if (avgSpo2 > 0) {
            ((avgSpo2 - 85) / 10).coerceIn(0.0, 1.0) * 15
        } else 0.0

        val total = (stepsScore + sleepScore + hrScore + spo2Score).roundToInt().coerceIn(0, 100)

        return HabitScore(
            score = total,
            factors = mapOf(
                "steps" to stepsScore.roundToInt(),
                "sleep" to sleepScore.roundToInt(),
                "heartRate" to hrScore.roundToInt(),
                "spo2" to spo2Score.roundToInt()
            )
        )
    }

    /**
     * 简单线性回归斜率（皮尔逊相关）
     * 仅用于方向判断，不做严格统计校验
     */
    private fun calculateSlope(values: List<Long>): Double {
        val n = values.size
        if (n < 2) return 0.0
        val xMean = (n - 1) / 2.0
        val yMean = values.average()
        var num = 0.0
        var den = 0.0
        for (i in values.indices) {
            val dx = i - xMean
            val dy = values[i] - yMean
            num += dx * dy
            den += dx * dx
        }
        return if (den == 0.0) 0.0 else num / den
    }

    /**
     * 生成健康建议（基于规则模板，不依赖AI模型时也能输出）
     * 返回建议文本列表，可被 ModelRouter 包装为 Prompt 进一步优化
     */
    suspend fun generateRuleBasedAdvice(): List<String> {
        val advice = mutableListOf<String>()
        val summary = getTodaySummary()
        val sleepTrend = analyzeSleepTrend(7)
        val stepsTrend = analyzeStepsTrend(7)

        // 今日步数
        if (summary.totalSteps < DAILY_TARGET_STEPS * 0.5f) {
            advice += "今日步数仅 ${summary.totalSteps}，距离目标还有 ${DAILY_TARGET_STEPS - summary.totalSteps} 步，起身活动一下吧！"
        } else if (summary.totalSteps < DAILY_TARGET_STEPS) {
            advice += "今日已走 ${summary.totalSteps} 步，再加把劲就能达成 $DAILY_TARGET_STEPS 步目标！"
        }

        // 睡眠
        if (sleepTrend.severity != TrendSeverity.NONE) {
            advice += when (sleepTrend.severity) {
                TrendSeverity.SEVERE -> "最近 ${sleepTrend.consecutiveInsufficientDays} 天连续睡眠不足，强烈建议调整作息，保证每天7小时睡眠。"
                TrendSeverity.MEDIUM -> "近一周睡眠质量有所下降，建议减少睡前电子设备使用，提前入睡。"
                else -> "近几日睡眠偏少，注意午休或提前就寝。"
            }
        }

        // 心率
        if (summary.avgRestingHr in 81.0..100.0) {
            advice += "近期静息心率偏高（${summary.avgRestingHr.toInt()} BPM），可能与压力或疲劳有关，注意放松。"
        } else if (summary.avgRestingHr > 0 && summary.avgRestingHr < 55) {
            advice += "静息心率较低（${summary.avgRestingHr.toInt()} BPM），如无运动习惯建议就医排查。"
        }

        // 血氧
        if (summary.minSpo2 in 1.0..92.0) {
            advice += "近期血氧最低值偏低（${summary.minSpo2.toInt()}%），注意深呼吸，必要时就医。"
        }

        // 压力
        if (summary.avgStress in 60.0..100.0) {
            advice += "平均压力指数较高（${summary.avgStress.toInt()}），建议尝试5分钟深呼吸或短暂休息。"
        }

        if (advice.isEmpty()) {
            advice += "各项指标状态良好，继续保持健康的生活习惯！"
        }

        return advice
    }
}

// ==================== 统计数据类 ====================

/**
 * 单天健康统计汇总
 */
data class DailyStats(
    val startTime: Long,
    val endTime: Long,
    val totalSteps: Long = 0L,
    val totalCalories: Long = 0L,
    val totalActiveMinutes: Int = 0,
    val avgHeartRate: Double = 0.0,
    val maxHeartRate: Int = 0,
    val minHeartRate: Int = 0,
    val avgRestingHr: Double = 0.0,
    val avgSpo2: Double = 0.0,
    val minSpo2: Double = 0.0,
    val avgStress: Double = 0.0,
    val sleepMinutes: Int = 0,
    val deepSleepMinutes: Int = 0,
    val sleepEfficiency: Double = 0.0,
    val avgHrvRmssd: Double = 0.0
) {
    /** 日期标签（MM-dd格式） */
    val dateLabel: String
        get() {
            val sdf = java.text.SimpleDateFormat("MM-dd", java.util.Locale.CHINA)
            return sdf.format(java.util.Date(startTime))
        }

    /** 步数是否达标 */
    val isStepsGoalReached: Boolean get() = totalSteps >= HealthRepository.DAILY_TARGET_STEPS

    /** 睡眠是否充足 */
    val isSleepSufficient: Boolean get() = sleepMinutes >= HealthRepository.DAILY_RECOMMENDED_SLEEP_MIN *
            HealthRepository.SLEEP_INSUFFICIENT_RATIO
}

/** 趋势严重级别 */
enum class TrendSeverity {
    NONE,      // 正常
    MILD,      // 轻微
    MEDIUM,    // 中等
    SEVERE     // 严重
}

/** 趋势方向 */
enum class TrendDirection {
    IMPROVING,  // 改善
    STABLE,     // 稳定
    DECLINING   // 变差
}

/** 睡眠趋势分析结果 */
data class SleepTrendAnalysis(
    val totalDays: Int,
    val insufficientDays: Int,
    val consecutiveInsufficientDays: Int,
    val averageSleepMinutes: Int,
    val recommendedMinutes: Int,
    val severity: TrendSeverity
)

/** 步数趋势分析结果 */
data class StepsTrendAnalysis(
    val totalDays: Int,
    val validDays: Int,
    val averageSteps: Long,
    val targetSteps: Int,
    val goalReachDays: Int,
    val goalReachRate: Float,
    val direction: TrendDirection,
    val slope: Double,
    val severity: TrendSeverity
)

/** 综合习惯评分 */
data class HabitScore(
    val score: Int,
    val factors: Map<String, Int>
) {
    /** 评分等级 */
    val grade: String
        get() = when {
            score >= 90 -> "A+ (优秀)"
            score >= 80 -> "A (良好)"
            score >= 70 -> "B (中等)"
            score >= 60 -> "C (及格)"
            else -> "D (需改善)"
        }
}
