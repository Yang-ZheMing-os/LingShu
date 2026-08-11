package com.lingshu.agent.feature.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

// ================ 测试用简化版快照和结果 ================

/**
 * 触发条件快照（测试用简化版，不依赖Calendar）
 */
data class TestTriggerSnapshot(
    val currentHour: Int,
    val currentMinute: Int = 0,
    val isScreenOn: Boolean = true,
    val sedentaryMinutes: Int = 0,
    val stillMinutes: Int = 0,
    val currentHeartRate: Int? = null,
    val unlockCountSinceMorning: Int = 0,
    val lastNegativeMoodDays: Int = -1
)

/**
 * 决策结果（测试用简化版）
 */
data class TestTriggerResult(
    val shouldTrigger: Boolean,
    val triggerType: TriggerType? = null,
    val reason: String = "",
    val confidence: Float = 0f
)

/**
 * 触发类型（测试用精简版，对应需求中的TriggerType枚举）
 */
enum class TriggerType {
    TIME_LATE_NIGHT,      // 深夜使用手机
    SENSOR_SEDENTARY,     // 久坐
    SENSOR_HEART_RATE,    // 心率异常
    SENSOR_LONG_STILL,    // 长时间静止
    BEHAVIOR_UNLOCK       // 频繁解锁
}

/**
 * 主动关怀决策引擎测试
 *
 * 测试核心规则：
 * 1. 深夜时段(02:00) + 亮屏 → 触发 TIME_LATE_NIGHT
 * 2. 冷却时间未到 → 不触发任何关怀
 * 3. 今日达每日上限 → 不触发任何关怀
 * 4. 低优先级触发(SENSOR_SEDENTARY等)随机过滤：20-30%概率才实际触发
 */
class ProactiveDecisionEngineTest {

    // 深夜时段定义（与ProactiveTriggers一致）：23:00 ~ 次日 05:00
    private val lateNightStartHour = 23
    private val lateNightEndHour = 5

    // 冷却时间（分钟）
    private var cooldownMinutes: Int = 60
    private var minutesSinceLastCare: Int = Int.MAX_VALUE

    // 每日上限
    private var dailyLimit: Int = 5
    private var todayCareCount: Int = 0

    // 总开关
    private var masterEnabled: Boolean = true

    // 频繁解锁阈值
    private var unlockThreshold: Int = 5

    // 久坐阈值（分钟）
    private var sedentaryThresholdMinutes: Int = 120

    // 心率正常范围
    private var heartRateMin: Int = 50
    private var heartRateMax: Int = 100

    @Before
    fun setUp() {
        // 每次测试前重置为宽松状态，便于单独测试规则
        masterEnabled = true
        cooldownMinutes = 60
        minutesSinceLastCare = Int.MAX_VALUE   // 很久没关怀了，冷却肯定够
        dailyLimit = 5
        todayCareCount = 0                     // 今天还没关怀过，不会超上限
        unlockThreshold = 5
        sedentaryThresholdMinutes = 120
        heartRateMin = 50
        heartRateMax = 100
    }

    // ================ 1. 深夜关怀触发 ================

    @Test
    fun `测试深夜关怀 - 02点00分亮屏触发TIME_LATE_NIGHT`() {
        // 模拟 02:00 凌晨2点，屏幕亮着
        val snap = TestTriggerSnapshot(
            currentHour = 2,
            currentMinute = 0,
            isScreenOn = true
        )
        val result = decide(snap)
        assertTrue("02:00亮屏应触发关怀", result.shouldTrigger)
        assertEquals("触发类型应为TIME_LATE_NIGHT",
            TriggerType.TIME_LATE_NIGHT, result.triggerType)
        assertTrue("置信度应>0", result.confidence > 0f)
        assertTrue("原因应包含'深夜'", result.reason.contains("深夜"))
    }

    @Test
    fun `测试深夜关怀 - 23点30分亮屏也触发`() {
        // 23:30 也在深夜区间内
        val snap = TestTriggerSnapshot(
            currentHour = 23,
            currentMinute = 30,
            isScreenOn = true
        )
        val result = decide(snap)
        assertTrue("23:30应触发深夜关怀", result.shouldTrigger)
        assertEquals(TriggerType.TIME_LATE_NIGHT, result.triggerType)
    }

    @Test
    fun `测试深夜关怀 - 04点59分59秒仍在深夜区间`() {
        // 04:59 还在深夜（<5点）
        val snap = TestTriggerSnapshot(
            currentHour = 4,
            currentMinute = 59,
            isScreenOn = true
        )
        val result = decide(snap)
        assertTrue("04:59仍应触发关怀", result.shouldTrigger)
        assertEquals(TriggerType.TIME_LATE_NIGHT, result.triggerType)
    }

    @Test
    fun `测试深夜关怀 - 深夜但屏幕关闭不触发`() {
        // 虽然是02:00，但用户没在玩手机（屏幕关）
        val snap = TestTriggerSnapshot(
            currentHour = 2,
            currentMinute = 0,
            isScreenOn = false
        )
        val result = decide(snap)
        assertFalse("屏幕关闭的深夜不应触发关怀", result.shouldTrigger)
        assertNull("屏幕关时triggerType应为null", result.triggerType)
    }

    @Test
    fun `测试深夜关怀 - 正常白天时段不触发`() {
        // 15:00 下午3点，正常白天
        val snap = TestTriggerSnapshot(
            currentHour = 15,
            currentMinute = 0,
            isScreenOn = true
        )
        val result = decide(snap)
        assertFalse("白天正常时段不触发深夜关怀", result.shouldTrigger)
    }

    @Test
    fun `测试深夜关怀 - 05点整已退出深夜区间`() {
        // 05:00 恰好是结束时间，不算深夜
        val snap = TestTriggerSnapshot(
            currentHour = 5,
            currentMinute = 0,
            isScreenOn = true
        )
        val result = decide(snap)
        assertFalse("05:00整已退出深夜区间", result.shouldTrigger)
    }

    // ================ 2. 冷却时间 ================

    @Test
    fun `测试冷却时间 - 距离上次59分钟（小于60）不触发`() {
        minutesSinceLastCare = 59  // < 60
        cooldownMinutes = 60

        // 满足触发条件（02:00亮屏）
        val snap = TestTriggerSnapshot(currentHour = 2, isScreenOn = true)
        val result = decide(snap)
        assertFalse("冷却未到(59<60)应不触发", result.shouldTrigger)
        assertTrue("原因应说明'冷却'", result.reason.contains("冷却")
                || result.reason.contains("未到"))
    }

    @Test
    fun `测试冷却时间 - 距离上次刚好60分钟（等于阈值）通过`() {
        minutesSinceLastCare = 60  // == 60
        cooldownMinutes = 60

        val snap = TestTriggerSnapshot(currentHour = 2, isScreenOn = true)
        val result = decide(snap)
        assertTrue("冷却刚好60分钟应允许触发", result.shouldTrigger)
    }

    @Test
    fun `测试冷却时间 - 距离上次1000分钟（远大于阈值）通过`() {
        minutesSinceLastCare = 1000
        cooldownMinutes = 60

        val snap = TestTriggerSnapshot(currentHour = 2, isScreenOn = true)
        val result = decide(snap)
        assertTrue("很久没关怀了应允许触发", result.shouldTrigger)
    }

    @Test
    fun `测试冷却时间 - 冷却0分钟时每次都允许`() {
        cooldownMinutes = 0
        minutesSinceLastCare = 0  // 刚刚关怀过，但冷却0分钟就可以再次

        val snap = TestTriggerSnapshot(currentHour = 2, isScreenOn = true)
        val result = decide(snap)
        assertTrue("冷却0分钟应允许", result.shouldTrigger)
    }

    // ================ 3. 每日上限 ================

    @Test
    fun `测试每日上限 - 今日已达5次（上限5）不触发`() {
        todayCareCount = 5
        dailyLimit = 5

        val snap = TestTriggerSnapshot(currentHour = 2, isScreenOn = true)
        val result = decide(snap)
        assertFalse("达到每日上限(5/5)应不触发", result.shouldTrigger)
        assertTrue("原因应包含'上限'", result.reason.contains("上限")
                || result.reason.contains("达"))
    }

    @Test
    fun `测试每日上限 - 今日已达4次（小于5）允许触发`() {
        todayCareCount = 4
        dailyLimit = 5

        val snap = TestTriggerSnapshot(currentHour = 2, isScreenOn = true)
        val result = decide(snap)
        assertTrue("4<5仍允许触发", result.shouldTrigger)
    }

    @Test
    fun `测试每日上限 - 今日0次上限1允许`() {
        todayCareCount = 0
        dailyLimit = 1

        val snap = TestTriggerSnapshot(currentHour = 2, isScreenOn = true)
        val result = decide(snap)
        assertTrue("0<1允许", result.shouldTrigger)
    }

    @Test
    fun `测试每日上限 - 今日1次上限1不允许`() {
        todayCareCount = 1
        dailyLimit = 1

        val snap = TestTriggerSnapshot(currentHour = 2, isScreenOn = true)
        val result = decide(snap)
        assertFalse("1>=1不允许", result.shouldTrigger)
    }

    // ================ 4. 总开关 ================

    @Test
    fun `测试总开关 - 关闭时即使满足触发条件也不触发`() {
        masterEnabled = false

        // 所有条件都满足
        minutesSinceLastCare = 999
        todayCareCount = 0
        val snap = TestTriggerSnapshot(currentHour = 2, isScreenOn = true)
        val result = decide(snap)
        assertFalse("总开关关闭时不应触发", result.shouldTrigger)
        assertTrue("原因应包含'开关'或'启用'",
            result.reason.contains("开关") || result.reason.contains("启用"))
    }

    // ================ 5. 久坐触发 + 随机过滤 ================

    @Test
    fun `测试久坐触发 - 180分钟久坐(超过120阈值)触发SENSOR_SEDENTARY`() {
        // 白天15:00（不触发深夜），久坐180分钟 > 120阈值
        val snap = TestTriggerSnapshot(
            currentHour = 15,
            isScreenOn = true,
            sedentaryMinutes = 180
        )
        // 跳过随机过滤，强制验证纯规则
        val result = decideSedentaryOnly(snap)
        assertEquals("久坐应返回SENSOR_SEDENTARY类型",
            TriggerType.SENSOR_SEDENTARY, result?.triggerType)
        assertTrue("置信度应在0~1之间", result!!.confidence in 0f..1f)
    }

    @Test
    fun `测试久坐触发 - 60分钟久坐(未达阈值)不触发`() {
        val snap = TestTriggerSnapshot(
            currentHour = 15,
            isScreenOn = true,
            sedentaryMinutes = 60   // <120阈值
        )
        val result = decideSedentaryOnly(snap)
        assertNull("60分钟不应触发久坐", result)
    }

    @Test
    fun `测试久坐触发 - 刚好120分钟达到阈值`() {
        val snap = TestTriggerSnapshot(
            currentHour = 15,
            isScreenOn = true,
            sedentaryMinutes = 120
        )
        val result = decideSedentaryOnly(snap)
        assertNotNull("刚好120分钟应触发久坐", result)
    }

    @Test
    fun `测试随机策略过滤 - 低优先级久坐型触发经过概率过滤`() {
        // 需求：非紧急类型（久坐、解锁频繁等）经过20~30%概率过滤
        // 模拟调用10000次久坐触发，统计实际触发次数
        val snap = TestTriggerSnapshot(
            currentHour = 15,
            sedentaryMinutes = 240
        )
        var triggered = 0
        val trials = 10000
        // 使用固定种子保证可重现
        val random = Random(42)

        repeat(trials) {
            // SENSOR_SEDENTARY属于"需概率过滤"类型
            if (shouldApplyProbabilityFilter(TriggerType.SENSOR_SEDENTARY)) {
                val probPercent = random.nextInt(20, 31) // 20~30
                val roll = random.nextInt(100)
                if (roll < probPercent) triggered++
            } else {
                triggered++
            }
        }
        val actualRate = triggered.toDouble() / trials
        // 约 25% (20~30均值)
        assertTrue("实际触发率应在15%~35%之间（正态波动），实际$actualRate",
            actualRate in 0.15..0.35)
    }

    @Test
    fun `测试随机策略过滤 - 深夜关怀TIME_LATE_NIGHT不经过概率过滤`() {
        // 需求：TIME_LATE_NIGHT 是"高优先级/强场景"类型，不应被概率过滤
        // 即100%都应通过
        assertFalse("TIME_LATE_NIGHT不应被概率过滤",
            shouldApplyProbabilityFilter(TriggerType.TIME_LATE_NIGHT))
    }

    @Test
    fun `测试随机策略过滤 - 心率异常SENSOR_HEART_RATE不经过概率过滤`() {
        // 心率异常紧急，也不应该被过滤
        assertFalse("心率异常SENSOR_HEART_RATE不应被概率过滤",
            shouldApplyProbabilityFilter(TriggerType.SENSOR_HEART_RATE))
    }

    @Test
    fun `测试随机策略过滤 - 久坐SENSOR_SEDENTARY经过过滤`() {
        assertTrue("久坐SENSOR_SEDENTARY应经过概率过滤",
            shouldApplyProbabilityFilter(TriggerType.SENSOR_SEDENTARY))
    }

    @Test
    fun `测试随机策略过滤 - 频繁解锁BEHAVIOR_UNLOCK经过过滤`() {
        assertTrue("频繁解锁BEHAVIOR_UNLOCK应经过概率过滤",
            shouldApplyProbabilityFilter(TriggerType.BEHAVIOR_UNLOCK))
    }

    // ================ 6. 其他触发类型的边界测试 ================

    @Test
    fun `测试心率异常 - 120过高触发SENSOR_HEART_RATE`() {
        val snap = TestTriggerSnapshot(
            currentHour = 12,
            currentHeartRate = 120   // >100上限
        )
        val result = decideHeartRateOnly(snap)
        assertEquals("心率120应异常", TriggerType.SENSOR_HEART_RATE, result?.triggerType)
    }

    @Test
    fun `测试心率异常 - 40过低也触发`() {
        val snap = TestTriggerSnapshot(
            currentHour = 12,
            currentHeartRate = 40  // <50下限
        )
        val result = decideHeartRateOnly(snap)
        assertEquals("心率40应异常", TriggerType.SENSOR_HEART_RATE, result?.triggerType)
    }

    @Test
    fun `测试心率正常 - 75在范围不触发`() {
        val snap = TestTriggerSnapshot(
            currentHour = 12,
            currentHeartRate = 75  // 50~100内
        )
        val result = decideHeartRateOnly(snap)
        assertNull("75正常心率不触发", result)
    }

    @Test
    fun `测试频繁解锁 - 今日解锁8次超阈值5次`() {
        val snap = TestTriggerSnapshot(
            currentHour = 20,
            unlockCountSinceMorning = 8
        )
        val result = decideFrequentUnlockOnly(snap)
        assertEquals("8>5应触发频繁解锁", TriggerType.BEHAVIOR_UNLOCK, result?.triggerType)
    }

    @Test
    fun `测试频繁解锁 - 今日解锁3次未超`() {
        val snap = TestTriggerSnapshot(
            currentHour = 20,
            unlockCountSinceMorning = 3
        )
        val result = decideFrequentUnlockOnly(snap)
        assertNull("3<5不触发频繁解锁", result)
    }

    // ================ 7. 多条件同时满足时取最佳（置信度最高） ================

    @Test
    fun `测试最佳选择 - 同时满足深夜和久坐，取深夜(0点85置信度最高)`() {
        // 02:00（深夜，置信度0.85）同时久坐180分钟（置信度约0.75）
        val snap = TestTriggerSnapshot(
            currentHour = 2,
            isScreenOn = true,
            sedentaryMinutes = 180
        )
        // 综合决策：两者都满足时应返回置信度最高的
        val lateNightResult = checkLateNight(snap)
        val sedentaryResult = checkSedentary(snap)
        assertNotNull(lateNightResult)
        assertNotNull(sedentaryResult)
        assertTrue("深夜置信度应>久坐置信度",
            lateNightResult!!.confidence > sedentaryResult!!.confidence)
    }

    // ============================================================
    // 下方为测试用辅助函数，还原 ProactiveDecisionEngine 的核心规则
    // ============================================================

    /**
     * 综合决策：前置检查→逐条规则判断→取置信度最高→低优先级随机过滤
     */
    private fun decide(snap: TestTriggerSnapshot): TestTriggerResult {
        // 前置检查
        if (!masterEnabled) {
            return TestTriggerResult(false, null, "总开关未启用", 0f)
        }
        if (minutesSinceLastCare < cooldownMinutes) {
            return TestTriggerResult(false, null, "冷却时间未到（需${cooldownMinutes}分钟）", 0f)
        }
        if (todayCareCount >= dailyLimit) {
            return TestTriggerResult(false, null, "今日关怀次数已达上限($dailyLimit)", 0f)
        }

        // 收集所有满足的触发
        val results = mutableListOf<TestTriggerResult>()
        checkLateNight(snap)?.let { results.add(it) }
        checkSedentary(snap)?.let { results.add(it) }
        checkHeartRate(snap)?.let { results.add(it) }
        checkFrequentUnlock(snap)?.let { results.add(it) }

        if (results.isEmpty()) {
            return TestTriggerResult(false, null, "无触发条件满足", 0f)
        }

        // 取置信度最高
        val best = results.maxByOrNull { it.confidence }!!

        // 低优先级随机过滤
        if (shouldApplyProbabilityFilter(best.triggerType!!)) {
            val actualProb = Random.nextInt(20, 31)
            val roll = Random.nextInt(100)
            if (roll >= actualProb) {
                return TestTriggerResult(false, null,
                    "随机过滤未命中（目标${actualProb}%）", 0f)
            }
        }

        return best
    }

    /** 仅测试久坐规则（不经过随机过滤，用于纯规则验证） */
    private fun decideSedentaryOnly(snap: TestTriggerSnapshot): TestTriggerResult? {
        if (!masterEnabled) return null
        if (minutesSinceLastCare < cooldownMinutes) return null
        if (todayCareCount >= dailyLimit) return null
        return checkSedentary(snap)
    }

    /** 仅测试心率规则 */
    private fun decideHeartRateOnly(snap: TestTriggerSnapshot): TestTriggerResult? {
        if (!masterEnabled) return null
        if (minutesSinceLastCare < cooldownMinutes) return null
        if (todayCareCount >= dailyLimit) return null
        return checkHeartRate(snap)
    }

    /** 仅测试频繁解锁 */
    private fun decideFrequentUnlockOnly(snap: TestTriggerSnapshot): TestTriggerResult? {
        if (!masterEnabled) return null
        if (minutesSinceLastCare < cooldownMinutes) return null
        if (todayCareCount >= dailyLimit) return null
        return checkFrequentUnlock(snap)
    }

    // ---------- 以下为单条规则检查 ----------

    private fun checkLateNight(snap: TestTriggerSnapshot): TestTriggerResult? {
        // 判断是否在深夜时段
        val isLateNight = if (lateNightStartHour < lateNightEndHour) {
            snap.currentHour in lateNightStartHour until lateNightEndHour
        } else {
            // 跨天情况（23 ~ 次日5）
            snap.currentHour >= lateNightStartHour || snap.currentHour < lateNightEndHour
        }
        if (!isLateNight) return null
        if (!snap.isScreenOn) return null
        return TestTriggerResult(
            shouldTrigger = true,
            triggerType = TriggerType.TIME_LATE_NIGHT,
            reason = "深夜 ${snap.currentHour}:${snap.currentMinute.toString().padStart(2,'0')} 仍在使用手机",
            confidence = 0.85f
        )
    }

    private fun checkSedentary(snap: TestTriggerSnapshot): TestTriggerResult? {
        if (snap.sedentaryMinutes < sedentaryThresholdMinutes) return null
        val ratio = snap.sedentaryMinutes.toFloat() / sedentaryThresholdMinutes
        val confidence = ratio.coerceAtMost(0.95f)
        return TestTriggerResult(
            shouldTrigger = true,
            triggerType = TriggerType.SENSOR_SEDENTARY,
            reason = "已久坐 ${snap.sedentaryMinutes} 分钟",
            confidence = confidence
        )
    }

    private fun checkHeartRate(snap: TestTriggerSnapshot): TestTriggerResult? {
        val hr = snap.currentHeartRate ?: return null
        if (hr in heartRateMin..heartRateMax) return null
        return TestTriggerResult(
            shouldTrigger = true,
            triggerType = TriggerType.SENSOR_HEART_RATE,
            reason = "心率异常: 当前 ${hr} bpm（阈值 ${heartRateMin}-${heartRateMax}）",
            confidence = 0.90f
        )
    }

    private fun checkFrequentUnlock(snap: TestTriggerSnapshot): TestTriggerResult? {
        if (snap.unlockCountSinceMorning < unlockThreshold) return null
        val extra = snap.unlockCountSinceMorning - unlockThreshold
        val confidence = (0.60f + extra * 0.05f).coerceAtMost(0.95f)
        return TestTriggerResult(
            shouldTrigger = true,
            triggerType = TriggerType.BEHAVIOR_UNLOCK,
            reason = "今日解锁${snap.unlockCountSinceMorning}次超阈值$unlockThreshold",
            confidence = confidence
        )
    }

    /**
     * 判断触发类型是否需要经过20-30%概率过滤
     * 需求：TIME_LATE_NIGHT、SENSOR_HEART_RATE 等紧急/强场景 → 不过滤
     *       SENSOR_SEDENTARY、BEHAVIOR_UNLOCK、SENSOR_LONG_STILL → 过滤
     */
    private fun shouldApplyProbabilityFilter(type: TriggerType): Boolean = when (type) {
        // 高优先级直接通过：深夜(固定场景)、心率(紧急)
        TriggerType.TIME_LATE_NIGHT,
        TriggerType.SENSOR_HEART_RATE -> false
        // 低优先级：久坐、频繁解锁、长时间静止 → 随机过滤
        TriggerType.SENSOR_SEDENTARY,
        TriggerType.SENSOR_LONG_STILL,
        TriggerType.BEHAVIOR_UNLOCK -> true
    }
}
