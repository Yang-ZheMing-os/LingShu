package com.lingshu.feature.proactive.domain

/**
 * 主动关怀实时诊断结果（设置页「诊断卡片」展示用）。
 *
 * 每一步对应 ProactiveServiceImpl.checkAndNotify 里的 4 关过滤，
 * 让用户在 UI 上直接看到"为什么没推"，而不是靠猜。
 */
data class ProactiveDiagnostics(
    /** 当前 HH:mm，例 "14:22" */
    val currentTimeText: String,
    /** 当前属于哪个饭点/睡前窗口（给人类看的文字） */
    val activeTimeWindows: List<String>,
    /** 第 1 关：总开关 enabled */
    val stepEnabled: CheckStep,
    /** 第 2 关：静音时段 */
    val stepQuietHours: CheckStep,
    /** 第 3 关：冷却与当日上限 */
    val stepCooldown: CheckStep,
    /** 第 4 关：各 trigger 命中情况 */
    val stepTriggers: Map<TriggerType, TriggerHitResult>,
    /** 最终结论（一句话） */
    val conclusion: String
)

/** 单步检查结果 */
data class CheckStep(
    /** true=通过 / false=未通过 */
    val passed: Boolean,
    /** 给人类看的说明，例 "enabled=true ✅" 或 "当日上限 5 次已用完 ❌" */
    val message: String
)

/** 单个 trigger 的命中结果 */
data class TriggerHitResult(
    /** 用户是否开启了该 trigger */
    val userEnabled: Boolean,
    /** 逻辑命中（当前时间/传感器命中触发条件） */
    val logicHit: Boolean,
    /** 是否被"非紧急 75% 概率过滤"筛掉 */
    val filteredByProbability: Boolean? = null,
    /** 说明文字，例 "当前 12:15 ∈ 午餐窗口(11:30-13:30) ✅" */
    val detail: String
) {
    /** 综合：最终会被选中返回吗 */
    val ultimatelyPicked: Boolean get() = userEnabled && logicHit && filteredByProbability != true
}
