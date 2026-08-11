package com.lingshu.agent.feature.proactive

/**
 * 主动关怀触发条件常量和配置类
 *
 * 定义所有触发类型、默认阈值、以及触发检测结果的数据结构。
 * 触发类型分为五大类：时间触发、行为触发、传感器触发、记忆触发、随机策略。
 */
object ProactiveTriggers {

    // ==================== 触发类型枚举 ====================

    /**
     * 触发类型枚举
     * 每种类型对应一种关怀场景，决策引擎根据不同类型选择不同的检测逻辑
     */
    enum class TriggerType(
        val displayName: String,
        val category: TriggerCategory,
        val description: String
    ) {
        // ---------- 时间触发 ----------
        LATE_NIGHT("深夜检测", TriggerCategory.TIME, "23:00-5:00期间检测用户是否仍在使用手机"),
        FIXED_TIME("固定时间点", TriggerCategory.TIME, "用户设置的固定提醒时间（如每日早安）"),
        USER_REMINDER("用户提醒时间", TriggerCategory.TIME, "用户自定义的个性化提醒时间"),

        // ---------- 行为触发 ----------
        FREQUENT_UNLOCK("频繁解锁", TriggerCategory.BEHAVIOR, "短时间内连续解锁超过阈值次数"),
        LATE_NIGHT_USAGE("深夜刷手机", TriggerCategory.BEHAVIOR, "深夜时段检测到活跃使用手机"),
        LONG_APP_USAGE("长时间同一App", TriggerCategory.BEHAVIOR, "连续使用同一应用超过阈值时间"),

        // ---------- 传感器触发 ----------
        SEDENTARY("久坐提醒", TriggerCategory.SENSOR, "长时间未检测到行走活动，久坐超过阈值"),
        HEART_RATE_ABNORMAL("心率异常", TriggerCategory.SENSOR, "检测到心率过高或过低"),
        LONG_STATIONARY("长时间静止", TriggerCategory.SENSOR, "设备长时间处于静止状态"),

        // ---------- 记忆触发 ----------
        BIRTHDAY("生日/纪念日", TriggerCategory.MEMORY, "生日或重要纪念日临近或当天"),
        NEGATIVE_MOOD_FOLLOWUP("负面情绪跟进", TriggerCategory.MEMORY, "上次检测到负面情绪后3天跟进关怀"),

        // ---------- 随机策略 ----------
        RANDOM_CARE("随机关怀", TriggerCategory.RANDOM, "20-30%概率触发的随机关怀，避免机械感");

        /** 获取触发类型的唯一标识Key（用于DataStore存储启用状态） */
        val prefKey: String get() = "trigger_${name.lowercase()}_enabled"
    }

    /**
     * 触发类别枚举
     * 用于UI分组展示和配置管理
     */
    enum class TriggerCategory(val displayName: String) {
        TIME("时间触发"),
        BEHAVIOR("行为触发"),
        SENSOR("传感器触发"),
        MEMORY("记忆触发"),
        RANDOM("随机策略")
    }

    // ==================== 默认阈值常量 ====================

    /** 深夜开始时间（小时，24小时制） */
    const val LATE_NIGHT_START_HOUR = 23

    /** 深夜结束时间（小时，24小时制） */
    const val LATE_NIGHT_END_HOUR = 5

    /** 频繁解锁计数阈值（连续解锁N次触发） */
    const val FREQUENT_UNLOCK_THRESHOLD = 5

    /** 频繁解锁统计时间窗口（毫秒），默认60分钟 */
    const val FREQUENT_UNLOCK_WINDOW_MS = 60 * 60 * 1000L

    /** 深夜刷手机活跃时长阈值（毫秒），默认15分钟 */
    const val LATE_NIGHT_USAGE_THRESHOLD_MS = 15 * 60 * 1000L

    /** 长时间同一App阈值（毫秒），默认90分钟 */
    const val LONG_APP_USAGE_THRESHOLD_MS = 90 * 60 * 1000L

    /** 久坐阈值（毫秒），默认2小时 */
    const val SEDENTARY_THRESHOLD_MS = 2 * 60 * 60 * 1000L

    /** 长时间静止阈值（毫秒），默认30分钟 */
    const val LONG_STATIONARY_THRESHOLD_MS = 30 * 60 * 1000L

    /** 心率正常范围下限（BPM） */
    const val HEART_RATE_LOWER_BOUND = 50

    /** 心率正常范围上限（BPM） */
    const val HEART_RATE_UPPER_BOUND = 100

    /** 负面情绪跟进间隔（毫秒），默认3天 */
    const val NEGATIVE_MOOD_FOLLOWUP_MS = 3L * 24 * 60 * 60 * 1000

    /** 随机关怀触发概率下限（20%） */
    const val RANDOM_PROBABILITY_LOWER = 0.20

    /** 随机关怀触发概率上限（30%） */
    const val RANDOM_PROBABILITY_UPPER = 0.30

    /** 默认冷却时间（毫秒），默认60分钟 */
    const val DEFAULT_COOLDOWN_MS = 60 * 60 * 1000L

    /** 默认每日关怀上限次数 */
    const val DEFAULT_DAILY_LIMIT = 5

    /** 关怀记录保留天数 */
    const val CARE_HISTORY_RETENTION_DAYS = 30

    // ==================== 触发检测结果 ====================

    /**
     * 触发决策结果
     *
     * @property shouldTrigger 是否应该触发关怀
     * @property triggerType 触发类型（仅当shouldTrigger为true时有意义）
     * @property triggerReason 人类可读的触发原因（用于调试和日志）
     * @property triggerData 附加的触发数据（如心率值、久坐时长等）
     * @property confidence 触发置信度（0.0-1.0），用于多个条件同时满足时的优先级排序
     */
    data class TriggerDecision(
        val shouldTrigger: Boolean,
        val triggerType: TriggerType? = null,
        val triggerReason: String = "",
        val triggerData: Map<String, Any> = emptyMap(),
        val confidence: Double = 0.0
    ) {
        companion object {
            /** 创建不触发的结果 */
            fun noTrigger(reason: String = "未满足任何触发条件"): TriggerDecision =
                TriggerDecision(
                    shouldTrigger = false,
                    triggerReason = reason
                )

            /** 创建触发的结果 */
            fun trigger(
                type: TriggerType,
                reason: String,
                data: Map<String, Any> = emptyMap(),
                confidence: Double = 1.0
            ): TriggerDecision =
                TriggerDecision(
                    shouldTrigger = true,
                    triggerType = type,
                    triggerReason = reason,
                    triggerData = data,
                    confidence = confidence
                )
        }
    }

    // ==================== 检测上下文数据 ====================

    /**
     * 传感器数据快照
     * 用于决策引擎判断传感器类触发条件
     */
    data class SensorSnapshot(
        /** 当前心率（BPM），null表示无数据 */
        val currentHeartRate: Int? = null,
        /** 过去N分钟的步数统计，key=时间窗口（分钟），value=步数 */
        val stepCounts: Map<Int, Int> = emptyMap(),
        /** 设备是否处于静止状态 */
        val isStationary: Boolean = false,
        /** 连续静止时长（毫秒） */
        val stationaryDurationMs: Long = 0L,
        /** 检测到的活动类型：STILL, WALKING, RUNNING, IN_VEHICLE等 */
        val detectedActivity: String? = null,
        /** 连续久坐时长（毫秒） */
        val sedentaryDurationMs: Long = 0L
    )

    /**
     * 用户行为数据快照
     * 用于决策引擎判断行为类触发条件
     */
    data class BehaviorSnapshot(
        /** 解锁记录时间戳列表（按时间升序） */
        val unlockTimestamps: List<Long> = emptyList(),
        /** 当前前台应用包名 */
        val currentForegroundApp: String? = null,
        /** 各应用使用时长统计（包名 -> 使用毫秒数），统计窗口由调用方决定 */
        val appUsageStats: Map<String, Long> = emptyMap(),
        /** 当前App连续使用时长（毫秒） */
        val currentAppDurationMs: Long = 0L,
        /** 深夜时段总活跃时长（毫秒） */
        val lateNightActiveDurationMs: Long = 0L
    )

    /**
     * 记忆数据快照
     * 用于决策引擎判断记忆类触发条件
     */
    data class MemorySnapshot(
        /** 生日/纪念日列表 */
        val memorialDays: List<MemoryEvent> = emptyList(),
        /** 上次检测到负面情绪的时间戳（null表示无记录） */
        val lastNegativeMoodTimestamp: Long? = null,
        /** 上次负面情绪的描述（可选） */
        val lastNegativeMoodDescription: String? = null
    )

    /**
     * 纪念日/生日数据
     */
    data class MemorialDay(
        val id: String = System.currentTimeMillis().toString(),
        /** 日期：月-日 格式，如 "07-15" 表示7月15日 */
        val date: String,
        /** 纪念类型 */
        val type: MemorialType = MemorialType.BIRTHDAY,
        /** 显示名称，如 "妈妈的生日" */
        val name: String,
        /** 备注信息（可选） */
        val note: String? = null
    )

    /**
     * 纪念日类型
     */
    enum class MemorialType(val displayName: String) {
        BIRTHDAY("生日"),
        ANNIVERSARY("纪念日"),
        FESTIVAL("节日"),
        CUSTOM("自定义")
    }

    /**
     * 自定义提醒时间
     */
    data class CustomReminderTime(
        val id: String = System.currentTimeMillis().toString(),
        /** 小时（0-23） */
        val hour: Int,
        /** 分钟（0-59） */
        val minute: Int,
        /** 启用的星期几，空列表表示每天都启用 */
        val daysOfWeek: List<Int> = emptyList(),
        /** 提醒名称 */
        val name: String = "",
        /** 是否启用 */
        val enabled: Boolean = true
    )

    // ==================== 用户反馈 ====================

    /**
     * 用户对关怀通知的反馈
     */
    enum class CareFeedback {
        /** 未处理（初始状态） */
        UNHANDLED,
        /** 用户点击了通知（已互动） */
        INTERACTED,
        /** 用户忽略/滑掉了通知 */
        DISMISSED,
        /** 用户明确表示不喜欢 */
        DISLIKED,
        /** 用户表示喜欢/有用 */
        LIKED
    }
}

// ==================== 需求指定的简化版触发类型枚举（5大类） ====================

/**
 * 触发类型大类枚举（需求指定）
 * 用于 ProactiveTrigger 数据类，对应 5 大类触发条件
 */
enum class TriggerTypeCategory {
    /** 时间触发（深夜/固定时间点/用户提醒） */
    TIME,
    /** 行为触发（连续解锁/深夜刷手机/同App停留过久） */
    BEHAVIOR,
    /** 传感器触发（久坐/心率异常/长时间静止） */
    SENSOR,
    /** 记忆触发（生日/纪念日/上次负面情绪跟进） */
    MEMORY,
    /** 随机策略（满足条件时20-30%概率触发，避免机械感） */
    RANDOM
}

/**
 * 主动触发结果数据类（需求指定）
 * 由决策引擎返回，包含触发类型、原因、优先级、额外信息
 *
 * @property type 触发大类
 * @property reason 人类可读的触发原因（用于调试和显示）
 * @property priority 优先级（数字越大越优先，0~100）
 * @property extras 附加触发数据（如心率值、久坐时长等）
 */
data class ProactiveTrigger(
    val type: TriggerTypeCategory,
    val reason: String,
    val priority: Int,
    val extras: Map<String, Any?> = emptyMap()
) {
    companion object {
        /** 根据 ProactiveConfig 中定义的详细 TriggerType 映射到大类 */
        fun fromDetailType(detailType: com.lingshu.agent.feature.proactive.TriggerType): TriggerTypeCategory =
            when (detailType) {
                com.lingshu.agent.feature.proactive.TriggerType.TIME_LATE_NIGHT,
                com.lingshu.agent.feature.proactive.TriggerType.MEAL_REMINDER,
                com.lingshu.agent.feature.proactive.TriggerType.TIME_FIXED,
                com.lingshu.agent.feature.proactive.TriggerType.TIME_USER_REMINDER ->
                    TriggerTypeCategory.TIME

                com.lingshu.agent.feature.proactive.TriggerType.BEHAVIOR_UNLOCK,
                com.lingshu.agent.feature.proactive.TriggerType.BEHAVIOR_LATE_APP_USE,
                com.lingshu.agent.feature.proactive.TriggerType.BEHAVIOR_LONG_APP_STAY ->
                    TriggerTypeCategory.BEHAVIOR

                com.lingshu.agent.feature.proactive.TriggerType.SENSOR_SEDENTARY,
                com.lingshu.agent.feature.proactive.TriggerType.SENSOR_HEART_RATE,
                com.lingshu.agent.feature.proactive.TriggerType.SENSOR_LONG_STILL,
                com.lingshu.agent.feature.proactive.TriggerType.LOW_LIGHT_FLASHLIGHT,
                com.lingshu.agent.feature.proactive.TriggerType.STRESS_INDEX,
                com.lingshu.agent.feature.proactive.TriggerType.RAIN_UMBRELLA ->
                    TriggerTypeCategory.SENSOR

                com.lingshu.agent.feature.proactive.TriggerType.MEMORY_BIRTHDAY,
                com.lingshu.agent.feature.proactive.TriggerType.MEMORY_ANNIVERSARY,
                com.lingshu.agent.feature.proactive.TriggerType.MEMORY_NEGATIVE_MOOD ->
                    TriggerTypeCategory.MEMORY

                com.lingshu.agent.feature.proactive.TriggerType.RANDOM ->
                    TriggerTypeCategory.RANDOM
            }
    }
}

/**
 * 关怀记录数据类（需求指定）
 * 表示每一条已经发送给用户的主动关怀
 *
 * @property id 记录唯一ID
 * @property triggerType 触发的大类（TIME/BEHAVIOR/SENSOR/MEMORY/RANDOM）
 * @property content 实际发送的关怀内容文本
 * @property sentAt 发送时间戳（毫秒）
 * @property userReaction 用户反馈（UNHANDLED / INTERACTED / DISMISSED / LIKED / DISLIKED）
 */
data class CareRecordEntry(
    val id: String = System.currentTimeMillis().toString(),
    val triggerType: TriggerTypeCategory,
    val content: String,
    val sentAt: Long = System.currentTimeMillis(),
    val userReaction: ProactiveTriggers.CareFeedback = ProactiveTriggers.CareFeedback.UNHANDLED
)
