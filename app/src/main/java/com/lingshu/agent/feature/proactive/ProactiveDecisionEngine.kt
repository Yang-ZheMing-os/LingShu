package com.lingshu.agent.feature.proactive

import android.util.Log
import kotlinx.coroutines.flow.first
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

@Singleton
class ProactiveDecisionEngine @Inject constructor(
    private val config: ProactiveConfig,
    private val repository: ProactiveCareRepository
) {
    private val tag = "ProactiveDecision"

    /**
     * 前置检查（需求指定）
     *
     * 统一进行：
     * 1. 总开关检查
     * 2. 冷却时间检查
     * 3. 每日上限检查
     *
     * 此方法不做具体触发类型判断，仅过滤"根本不可能触发"的场景，
     * 供 Service 层快速跳过，避免不必要的传感器/行为检测。
     *
     * @return <是否通过前置检查, 原因说明（不通过时给出人类可读原因）>
     */
    suspend fun preflightCheck(): Pair<Boolean, String> {
        if (!config.enabled.first()) {
            return Pair(false, "总开关未启用")
        }
        // 规格书：勿扰时段内静默不推送
        if (config.isInQuietHours()) {
            return Pair(false, "当前处于勿扰时段")
        }
        val cooldown = config.cooldownMinutes.first()
        val dailyLimit = config.dailyLimit.first()
        if (repository.getMinutesSinceLastCare() < cooldown) {
            return Pair(false, "冷却时间未到（需${cooldown}分钟）")
        }
        if (repository.getTodayCareCount() >= dailyLimit) {
            return Pair(false, "今日关怀次数已达上限($dailyLimit)")
        }
        return Pair(true, "通过前置检查")
    }

    /**
     * 便捷重载：自动 buildSnapshot 并执行一次完整决策
     *
     * 适用于 Service 层定期巡检的调用场景。
     * 返回 ProactiveTrigger（需求指定的数据类结构）。
     */
    suspend fun shouldTrigger(): ProactiveTrigger? {
        val snapshot = buildSnapshot()
        val result = shouldTrigger(snapshot)
        if (!result.shouldTrigger || result.triggerType == null) {
            return null
        }
        val trigger = result.triggerType
        val type = ProactiveTrigger.fromDetailType(trigger)
        val priority = when (type) {
            TriggerTypeCategory.TIME -> 80
            TriggerTypeCategory.BEHAVIOR -> 60
            TriggerTypeCategory.SENSOR -> 70
            TriggerTypeCategory.MEMORY -> 90
            TriggerTypeCategory.RANDOM -> 30
        }
        return ProactiveTrigger(
            type = type,
            reason = result.reason,
            priority = priority,
            extras = buildMap {
                result.confidence.let { put("confidence", it) }
                trigger.let { put("detailTrigger", it.name) }
                snapshot.currentHeartRate?.let { put("heartRate", it) }
                if (snapshot.sedentaryMinutes > 0) put("sedentaryMinutes", snapshot.sedentaryMinutes)
                if (snapshot.stillMinutes > 0) put("stillMinutes", snapshot.stillMinutes)
                snapshot.currentForegroundApp?.let { put("foregroundApp", it) }
            }
        )
    }

    /**
     * 核心决策函数（保留原有签名，兼容旧代码）
     *
     * 输出 TriggerResult，额外过滤：
     * - 非 MEMORY/TIME 高优先级类型会再次经过 20~30% 随机概率过滤，避免机械感（需求指定）
     */
    suspend fun shouldTrigger(snapshot: TriggerSnapshot): TriggerResult {
        if (!config.enabled.first()) {
            return TriggerResult(false, null, "总开关未启用", 0f)
        }
        if (config.isInQuietHours()) {
            return TriggerResult(false, null, "当前处于勿扰时段", 0f)
        }

        val cooldown = config.cooldownMinutes.first()
        val dailyLimit = config.dailyLimit.first()

        if (repository.getMinutesSinceLastCare() < cooldown) {
            return TriggerResult(false, null, "冷却时间未到", 0f)
        }
        if (repository.getTodayCareCount() >= dailyLimit) {
            return TriggerResult(false, null, "今日关怀次数已达上限", 0f)
        }

        val enabledTypes = config.enabledTriggerTypes.first()
        val results = mutableListOf<TriggerResult>()

        // ========== 规格书 P5-P6：7种核心触发类型 ==========
        if (enabledTypes.contains(TriggerType.TIME_LATE_NIGHT) &&
            repository.getMinutesSinceLastTrigger(TriggerType.TIME_LATE_NIGHT) >= 30) {
            checkLateNight(snapshot)?.let { results.add(it) }
        }
        if (enabledTypes.contains(TriggerType.MEAL_REMINDER) &&
            repository.getMinutesSinceLastTrigger(TriggerType.MEAL_REMINDER) >= 15) {
            checkMealReminder(snapshot)?.let { results.add(it) }
        }
        if (enabledTypes.contains(TriggerType.SENSOR_SEDENTARY)) {
            checkSedentary(snapshot)?.let { results.add(it) }
        }
        if (enabledTypes.contains(TriggerType.LOW_LIGHT_FLASHLIGHT)) {
            checkLowLight(snapshot)?.let { results.add(it) }
        }
        if (enabledTypes.contains(TriggerType.SENSOR_HEART_RATE) &&
            repository.getMinutesSinceLastTrigger(TriggerType.SENSOR_HEART_RATE) >= 5) {
            checkHeartRate(snapshot)?.let { results.add(it) }
        }
        if (enabledTypes.contains(TriggerType.STRESS_INDEX)) {
            checkStressIndex(snapshot)?.let { results.add(it) }
        }
        if (enabledTypes.contains(TriggerType.RAIN_UMBRELLA) &&
            repository.getMinutesSinceLastTrigger(TriggerType.RAIN_UMBRELLA) >= 60) {
            checkRainUmbrella(snapshot)?.let { results.add(it) }
        }

        // ========== 扩展触发类型（向后兼容） ==========
        if (enabledTypes.contains(TriggerType.TIME_USER_REMINDER) || enabledTypes.contains(TriggerType.TIME_FIXED)) {
            checkCustomReminders(snapshot)?.let { results.add(it) }
        }
        if (enabledTypes.contains(TriggerType.BEHAVIOR_UNLOCK)) {
            checkFrequentUnlock(snapshot)?.let { results.add(it) }
        }
        if (enabledTypes.contains(TriggerType.BEHAVIOR_LATE_APP_USE)) {
            checkLateNightAppUse(snapshot)?.let { results.add(it) }
        }
        if (enabledTypes.contains(TriggerType.BEHAVIOR_LONG_APP_STAY)) {
            checkLongAppStay(snapshot)?.let { results.add(it) }
        }
        if (enabledTypes.contains(TriggerType.SENSOR_LONG_STILL)) {
            checkLongStill(snapshot)?.let { results.add(it) }
        }
        if (enabledTypes.contains(TriggerType.MEMORY_BIRTHDAY)) {
            checkBirthday(snapshot)?.let { results.add(it) }
        }
        if (enabledTypes.contains(TriggerType.MEMORY_ANNIVERSARY)) {
            checkAnniversary(snapshot)?.let { results.add(it) }
        }
        if (enabledTypes.contains(TriggerType.MEMORY_NEGATIVE_MOOD)) {
            checkNegativeMoodFollowup(snapshot)?.let { results.add(it) }
        }
        if (enabledTypes.contains(TriggerType.RANDOM)) {
            checkRandom(snapshot)?.let { results.add(it) }
        }

        if (results.isEmpty()) {
            return TriggerResult(false, null, "无触发条件满足", 0f)
        }

        val best = results.maxByOrNull { it.confidence }
        Log.d(tag, "最佳触发结果(过滤前): $best")

        // =============== 需求：随机策略过滤（避免机械感）===============
        // 满足以下任意条件的触发类型，直接通过（无需概率过滤）：
        // - 时间类：固定提醒/深夜/自定义提醒（这些是用户明确设置或强场景触发）
        // - 记忆类：生日/纪念日/情绪跟进（这类事件错过就可惜了）
        // - 传感器类：心率异常（紧急触发）
        // 其余（连续解锁、久坐、长用APP、随机关怀）：20~30% 概率实际触发
        if (best != null && best.triggerType != null) {
            val needProbFilter = when (best.triggerType) {
                TriggerType.TIME_LATE_NIGHT,
                TriggerType.TIME_FIXED,
                TriggerType.TIME_USER_REMINDER,
                TriggerType.MEAL_REMINDER,
                TriggerType.RAIN_UMBRELLA,
                TriggerType.MEMORY_BIRTHDAY,
                TriggerType.MEMORY_ANNIVERSARY,
                TriggerType.MEMORY_NEGATIVE_MOOD,
                TriggerType.SENSOR_HEART_RATE,
                TriggerType.STRESS_INDEX ->
                    false
                else -> true
            }
            if (needProbFilter) {
                val actualProb = Random.nextInt(20, 31) // 20~30
                val roll = Random.nextInt(100)
                if (roll >= actualProb) {
                    Log.d(tag, "触发被概率过滤器拦截（目标概率 ${actualProb}%，roll=$roll），跳过本次关怀")
                    return TriggerResult(false, null, "随机过滤未命中（${actualProb}%）", 0f)
                }
            }
        }
        return best ?: TriggerResult(false, null, "", 0f)
    }

    /** 规格书：深夜未睡 23:30-05:00 + 屏幕亮屏，间隔30分钟 */
    private suspend fun checkLateNight(snap: TriggerSnapshot): TriggerResult? {
        // 规格书定义：23:30（23.5小时）起算至次日 5:00
        val lateNightStart = 23 * 60 + 30  // 23:30 = 1410 minutes
        val lateNightEnd = 5 * 60           // 05:00 = 300 minutes
        val currentMinutes = snap.currentHour * 60 + snap.currentMinute
        val isLate = currentMinutes >= lateNightStart || currentMinutes < lateNightEnd
        if (!isLate) return null
        if (!snap.isScreenOn) return null

        return TriggerResult(
            shouldTrigger = true,
            triggerType = TriggerType.TIME_LATE_NIGHT,
            reason = "深夜 ${snap.currentHour}:${snap.currentMinute.toString().padStart(2, '0')} 仍在使用手机，建议休息",
            confidence = 0.88f
        )
    }

    private suspend fun checkCustomReminders(snap: TriggerSnapshot): TriggerResult? {
        val reminders = config.customReminders.first() ?: return null
        val now = System.currentTimeMillis()
        val todayStr = "${snap.currentHour}:${snap.currentMinute}"
        reminders.filter { it.enabled }.forEach { r ->
            if (r.hour == snap.currentHour && kotlin.math.abs(r.minute - snap.currentMinute) <= 1) {
                if (r.daysOfWeek.isEmpty() || snap.currentDayOfWeek in r.daysOfWeek) {
                    return TriggerResult(
                        shouldTrigger = true,
                        triggerType = TriggerType.TIME_USER_REMINDER,
                        reason = "自定义提醒: ${r.name}",
                        confidence = 1.0f
                    )
                }
            }
        }
        return null
    }

    private suspend fun checkFrequentUnlock(snap: TriggerSnapshot): TriggerResult? {
        val threshold = config.unlockThreshold.first() ?: 5
        if (snap.unlockCountSinceMorning >= threshold) {
            return TriggerResult(
                shouldTrigger = true,
                triggerType = TriggerType.BEHAVIOR_UNLOCK,
                reason = "今日已解锁 ${snap.unlockCountSinceMorning} 次，超过阈值 $threshold",
                confidence = 0.6f + (snap.unlockCountSinceMorning - threshold) * 0.05f
            )
        }
        return null
    }

    private suspend fun checkLateNightAppUse(snap: TriggerSnapshot): TriggerResult? {
        val (start, end) = config.lateNightHours.first() ?: return null
        val isLate = if (start < end) {
            snap.currentHour in start until end
        } else {
            snap.currentHour >= start || snap.currentHour < end
        }
        if (!isLate) return null
        if (snap.currentForegroundApp.isNullOrBlank()) return null
        val minutesInApp = (System.currentTimeMillis() - snap.foregroundAppStartTime) / 60000L
        if (minutesInApp >= 10) {
            return TriggerResult(
                shouldTrigger = true,
                triggerType = TriggerType.BEHAVIOR_LATE_APP_USE,
                reason = "深夜使用应用已 ${minutesInApp} 分钟",
                confidence = 0.8f
            )
        }
        return null
    }

    private suspend fun checkLongAppStay(snap: TriggerSnapshot): TriggerResult? {
        val threshold = config.longAppThresholdMinutes.first() ?: 45
        if (snap.currentForegroundApp.isNullOrBlank()) return null
        val minutesInApp = (System.currentTimeMillis() - snap.foregroundAppStartTime) / 60000L
        if (minutesInApp >= threshold) {
            return TriggerResult(
                shouldTrigger = true,
                triggerType = TriggerType.BEHAVIOR_LONG_APP_STAY,
                reason = "长时间使用同一应用: ${minutesInApp} 分钟",
                confidence = 0.7f
            )
        }
        return null
    }

    private suspend fun checkSedentary(snap: TriggerSnapshot): TriggerResult? {
        val thresholdHours = config.sedentaryThresholdHours.first()
        val thresholdMinutes = thresholdHours * 60
        if (snap.sedentaryMinutes >= thresholdMinutes) {
            return TriggerResult(
                shouldTrigger = true,
                triggerType = TriggerType.SENSOR_SEDENTARY,
                reason = "已久坐 ${snap.sedentaryMinutes} 分钟",
                confidence = (snap.sedentaryMinutes.toFloat() / thresholdMinutes).coerceAtMost(0.95f)
            )
        }
        return null
    }

    /** 规格书：静息心率>100或<45 bpm，实时 */
    private suspend fun checkHeartRate(snap: TriggerSnapshot): TriggerResult? {
        val hr = snap.currentHeartRate ?: return null
        // 规格书阈值：<45 或 >100
        val lower = config.heartRateLower.first()
        val upper = config.heartRateUpper.first()
        if (hr < lower || hr > upper) {
            return TriggerResult(
                shouldTrigger = true,
                triggerType = TriggerType.SENSOR_HEART_RATE,
                reason = "心率异常: 当前 ${hr} bpm（正常范围 ${lower}-${upper}）",
                confidence = 0.92f
            )
        }
        return null
    }

    private suspend fun checkLongStill(snap: TriggerSnapshot): TriggerResult? {
        val threshold = config.stillThresholdMinutes.first() ?: 60
        if (snap.stillMinutes >= threshold) {
            return TriggerResult(
                shouldTrigger = true,
                triggerType = TriggerType.SENSOR_LONG_STILL,
                reason = "长时间静止 ${snap.stillMinutes} 分钟",
                confidence = 0.65f
            )
        }
        return null
    }

    private suspend fun checkBirthday(snap: TriggerSnapshot): TriggerResult? {
        if (snap.upcomingBirthdays.isEmpty()) return null
        val cal = Calendar.getInstance()
        val todayMonth = cal.get(Calendar.MONTH) + 1
        val todayDay = cal.get(Calendar.DAY_OF_MONTH)
        val events = config.memoryEvents.first() ?: return null
        events.filter { it.type == "BIRTHDAY" }.forEach { e ->
            val daysDiff = daysUntil(todayMonth, todayDay, e.month, e.day)
            if (daysDiff <= 3 && daysDiff >= 0) {
                val msg = if (daysDiff == 0) "今天是 ${e.title} 的生日！" else "${e.title} 的生日还有 ${daysDiff} 天"
                return TriggerResult(true, TriggerType.MEMORY_BIRTHDAY, msg, 0.95f)
            }
        }
        return null
    }

    private suspend fun checkAnniversary(snap: TriggerSnapshot): TriggerResult? {
        if (snap.upcomingAnniversaries.isEmpty()) return null
        val cal = Calendar.getInstance()
        val todayMonth = cal.get(Calendar.MONTH) + 1
        val todayDay = cal.get(Calendar.DAY_OF_MONTH)
        val events = config.memoryEvents.first() ?: return null
        events.filter { it.type == "ANNIVERSARY" }.forEach { e ->
            val daysDiff = daysUntil(todayMonth, todayDay, e.month, e.day)
            if (daysDiff <= 3 && daysDiff >= 0) {
                val msg = if (daysDiff == 0) "今天是 ${e.title}！" else "${e.title} 还有 ${daysDiff} 天"
                return TriggerResult(true, TriggerType.MEMORY_ANNIVERSARY, msg, 0.9f)
            }
        }
        return null
    }

    private suspend fun checkNegativeMoodFollowup(snap: TriggerSnapshot): TriggerResult? {
        if (snap.lastNegativeMoodDays in 3..7) {
            return TriggerResult(
                shouldTrigger = true,
                triggerType = TriggerType.MEMORY_NEGATIVE_MOOD,
                reason = "上次情绪低落已过去 ${snap.lastNegativeMoodDays} 天，跟进关怀",
                confidence = 0.75f
            )
        }
        return null
    }

    private suspend fun checkRandom(snap: TriggerSnapshot): TriggerResult? {
        if (!snap.isScreenOn) return null
        val prob = config.randomProbability.first() ?: 25
        if (snap.totalDaysWithApp < 3) return null
        val roll = Random.nextInt(100)
        if (roll < prob / 4) {
            return TriggerResult(
                shouldTrigger = true,
                triggerType = TriggerType.RANDOM,
                reason = "随机关怀触发",
                confidence = 0.25f + prob * 0.005f
            )
        }
        return null
    }

    // ==================== 规格书 P5 新增检查方法 ====================

    /** 规格书：饭点未进食 07:00-09:00 / 11:30-13:30 / 17:30-19:30，间隔15分钟 */
    private suspend fun checkMealReminder(snap: TriggerSnapshot): TriggerResult? {
        if (!snap.isScreenOn) return null
        val currentMinutes = snap.currentHour * 60 + snap.currentMinute
        val mealWindows = listOf(
            7 * 60 to 9 * 60,          // 早餐 07:00-09:00
            11 * 60 + 30 to 13 * 60 + 30, // 午餐 11:30-13:30
            17 * 60 + 30 to 19 * 60 + 30  // 晚餐 17:30-19:30
        )
        val currentMeal = mealWindows.firstOrNull { (start, end) ->
            currentMinutes in start..end
        } ?: return null
        val mealName = when (currentMeal) {
            mealWindows[0] -> "早餐"
            mealWindows[1] -> "午餐"
            mealWindows[2] -> "晚餐"
            else -> "用餐"
        }
        return TriggerResult(
            shouldTrigger = true,
            triggerType = TriggerType.MEAL_REMINDER,
            reason = "现在是${mealName}时间 (${snap.currentHour}:${snap.currentMinute.toString().padStart(2, '0')})，记得按时吃饭",
            confidence = 0.82f
        )
    }

    /** 规格书：暗光手电筒 环境光<10 lux + 步态检测，实时 */
    private suspend fun checkLowLight(snap: TriggerSnapshot): TriggerResult? {
        val lux = snap.ambientLightLux ?: return null
        val luxThreshold = config.lowLightLuxThreshold.first()
        if (lux < luxThreshold && snap.stepCountLast5Min > 0) {
            return TriggerResult(
                shouldTrigger = true,
                triggerType = TriggerType.LOW_LIGHT_FLASHLIGHT,
                reason = "环境光仅 ${lux} lux，检测到步态活动，建议开启手电筒",
                confidence = 0.85f
            )
        }
        return null
    }

    /** 规格书：压力指数 HRV计算，指数>0.7，实时 */
    private suspend fun checkStressIndex(snap: TriggerSnapshot): TriggerResult? {
        val stress = snap.stressIndex ?: return null
        val thresholdFloat = config.stressIndexThreshold.first() / 100f
        if (stress > thresholdFloat) {
            return TriggerResult(
                shouldTrigger = true,
                triggerType = TriggerType.STRESS_INDEX,
                reason = "压力指数 ${String.format("%.2f", stress)}，高于阈值 ${String.format("%.2f", thresholdFloat)}，建议休息",
                confidence = 0.78f
            )
        }
        return null
    }

    /** 规格书：雨天带伞 降水概率>60%，每小时 */
    private suspend fun checkRainUmbrella(snap: TriggerSnapshot): TriggerResult? {
        val probability = snap.rainProbability ?: return null
        val probThreshold = config.rainProbabilityThreshold.first()
        if (probability > probThreshold) {
            return TriggerResult(
                shouldTrigger = true,
                triggerType = TriggerType.RAIN_UMBRELLA,
                reason = "降水概率 ${probability}%，超过阈值 ${probThreshold}%，建议带伞",
                confidence = 0.75f
            )
        }
        return null
    }

    private fun daysUntil(fromMonth: Int, fromDay: Int, toMonth: Int, toDay: Int): Int {
        val cal1 = Calendar.getInstance().apply {
            set(Calendar.MONTH, fromMonth - 1)
            set(Calendar.DAY_OF_MONTH, fromDay)
            val y = get(Calendar.YEAR)
            set(Calendar.YEAR, if (toMonth < fromMonth || (toMonth == fromMonth && toDay < fromDay)) y + 1 else y)
        }
        val cal2 = Calendar.getInstance().apply {
            set(Calendar.MONTH, toMonth - 1)
            set(Calendar.DAY_OF_MONTH, toDay)
            set(Calendar.YEAR, cal1.get(Calendar.YEAR))
        }
        val diff = cal2.timeInMillis - cal1.timeInMillis
        return (diff / 86400000L).toInt()
    }

    fun buildSnapshot(
        currentHeartRate: Int? = null,
        sedentaryMinutes: Int = 0,
        stillMinutes: Int = 0,
        currentForegroundApp: String? = null,
        foregroundAppStartTime: Long = 0L,
        isScreenOn: Boolean = true,
        upcomingBirthdays: List<String> = emptyList(),
        upcomingAnniversaries: List<String> = emptyList(),
        totalDaysWithApp: Int = 30,
        ambientLightLux: Int? = null,
        stepCountLast5Min: Int = 0,
        stressIndex: Float? = null,
        rainProbability: Int? = null
    ): TriggerSnapshot {
        val cal = Calendar.getInstance()
        return TriggerSnapshot(
            currentHour = cal.get(Calendar.HOUR_OF_DAY),
            currentMinute = cal.get(Calendar.MINUTE),
            currentDayOfWeek = cal.get(Calendar.DAY_OF_WEEK),
            isScreenOn = isScreenOn,
            unlockCountSinceMorning = repository.getUnlockCountSinceMorning(),
            lastUnlockTime = repository.getLastUnlockTime(),
            currentForegroundApp = currentForegroundApp,
            foregroundAppStartTime = foregroundAppStartTime,
            sedentaryMinutes = sedentaryMinutes,
            currentHeartRate = currentHeartRate,
            heartRateThresholdMin = 50,
            heartRateThresholdMax = 100,
            stillMinutes = stillMinutes,
            upcomingBirthdays = upcomingBirthdays,
            upcomingAnniversaries = upcomingAnniversaries,
            lastNegativeMoodDays = repository.getDaysSinceLastNegativeMood(),
            lastCareTime = repository.getLastCareTime(),
            todayCareCount = repository.getTodayCareCount(),
            totalDaysWithApp = totalDaysWithApp,
            ambientLightLux = ambientLightLux,
            stepCountLast5Min = stepCountLast5Min,
            stressIndex = stressIndex,
            rainProbability = rainProbability
        )
    }
}

/**
 * 规格书 P5 决策结果数据类
 * 决策层返回此结构，包含通知决策、触发类型、证据和建议动作
 */
data class DecisionResult(
    val shouldNotify: Boolean,
    val triggerType: TriggerType? = null,
    val evidence: String = "",
    val suggestedAction: String = ""
)
