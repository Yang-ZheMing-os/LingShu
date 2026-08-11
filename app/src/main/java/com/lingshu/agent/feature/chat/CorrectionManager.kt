package com.lingshu.agent.feature.chat

import android.util.Log
import com.lingshu.agent.core.database.dao.CorrectionDao
import com.lingshu.agent.core.database.entity.CorrectionEntity
import com.lingshu.agent.feature.personality.PersonalityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 纠正管理器（模块6 纠正机制 — 对齐规格书）
 *
 * 职责：
 * 1. 存储纠正记录：id / originalInput / originalResponse / correction / applied / timestamp
 * 2. 语音纠正检测："不对/错了/不是这样的" → 询问"哪里不对？"
 * 3. 气泡长按→"纠正"选项→弹出纠正输入框（UI 侧集成）
 * 4. 每周一次自动分析未应用纠正，提取共性模式调整 traits
 * 5. 设置页"纠正记录"列表
 */
@Singleton
class CorrectionManager @Inject constructor(
    private val correctionDao: CorrectionDao,
    private val personalityManager: PersonalityManager
) {

    companion object {
        private const val TAG = "CorrectionManager"

        /** 纠正检测关键词 */
        private val CORRECTION_DETECTION_PATTERNS = listOf(
            Regex("不对|错了|不是(这样|那样)的|搞错了|说错了|错了哦"),
            Regex("应该是|改成|正确的|应该是这样的|其实是"),
            Regex("重新说|再来|重来|再试|换一个说法"),
            Regex("你理解错了|你没懂|你没理解|误解了")
        )

        /** 每周分析间隔：7天 */
        private const val WEEKLY_ANALYSIS_MS = 7 * 24 * 60 * 60 * 1000L
    }

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ==================== 纠正检测 ====================

    /**
     * 检测用户消息是否包含纠正意图
     * 返回需要询问的提示文本，null 表示无需处理
     */
    fun detectCorrection(userMessage: String): String? {
        val lower = userMessage.lowercase()
        val matched = CORRECTION_DETECTION_PATTERNS.any { it.containsMatchIn(lower) }
        if (!matched) return null

        // 如果用户已经提供了完整的纠正内容，直接保存
        val hasContent = Regex("应该是(.{2,})|改成(.{2,})|正确的(.{2,})")
            .containsMatchIn(userMessage)
        if (hasContent) {
            return null // 有完整纠正内容，走 recordCorrection 流程
        }

        return "哪里不对？请告诉我正确的回答应该是什么，我会记住并改进。"
    }

    /**
     * 判断是否应触发纠正询问（语音场景专用）
     * true = 需要追问用户"哪里不对？"
     */
    fun shouldAskForCorrection(userMessage: String): Boolean {
        val lower = userMessage.lowercase()
        // 只有模糊否定（只有"不对"但没有提供改正内容）时才询问
        val isVague = (lower.contains("不对") || lower.contains("错了") || lower.contains("不是这样的")) &&
                !lower.contains("应该是") &&
                !lower.contains("改成") &&
                !lower.contains("正确的")
        return isVague
    }

    // ==================== 纠正记录 CRUD ====================

    /**
     * 保存纠正记录
     */
    fun recordCorrection(
        originalInput: String,
        originalResponse: String,
        correction: String
    ) {
        val entity = CorrectionEntity(
            originalInput = originalInput.take(500),
            originalResponse = originalResponse.take(1000),
            correction = correction.take(500)
        )
        managerScope.launch {
            try {
                correctionDao.insertCorrection(entity)
                Log.d(TAG, "纠正记录已保存")
            } catch (e: Exception) {
                Log.e(TAG, "保存纠正记录失败", e)
            }
        }
    }

    /**
     * 标记纠正已应用
     */
    fun markCorrectionApplied(id: String) {
        managerScope.launch {
            try {
                correctionDao.markApplied(id)
            } catch (e: Exception) {
                Log.e(TAG, "标记纠正失败", e)
            }
        }
    }

    /**
     * 获取未应用的纠正记录（外部通过 DAO Flow 观察）
     */
    suspend fun getUnappliedCorrections(): List<CorrectionEntity> {
        return try {
            correctionDao.getUnappliedCorrections()
        } catch (e: Exception) {
            Log.e(TAG, "获取未应用纠正失败", e)
            emptyList()
        }
    }

    // ==================== 每周自动分析 ====================

    /**
     * 每周一次自动分析：提取未应用纠正的共性模式，调整 traits
     */
    suspend fun runWeeklyAnalysis() {
        val corrections = correctionDao.getUnappliedCorrections()
        if (corrections.isEmpty()) return

        // 分析纠正模式
        val patterns = analyzePatterns(corrections)
        if (patterns.isEmpty()) return

        Log.i(TAG, "每周纠正分析：发现 ${patterns.size} 个共性模式，共 ${corrections.size} 条纠正")

        // 根据模式微调人格（delta < 0.05 已在 PersonalityManager 中约束）
        patterns.forEach { (dimension, delta) ->
            val current = personalityManager.currentPersonality.value
            val traits = current.traits
            val adjusted = when (dimension) {
                "agreeableness" -> traits.copy(agreeableness = (traits.agreeableness + delta).coerceIn(0.0, 1.0))
                "conscientiousness" -> traits.copy(conscientiousness = (traits.conscientiousness + delta).coerceIn(0.0, 1.0))
                "openness" -> traits.copy(openness = (traits.openness + delta).coerceIn(0.0, 1.0))
                "extraversion" -> traits.copy(extraversion = (traits.extraversion + delta).coerceIn(0.0, 1.0))
                "neuroticism" -> traits.copy(neuroticism = (traits.neuroticism + delta).coerceIn(0.0, 1.0))
                else -> null
            }
            if (adjusted != null && adjusted != traits) {
                personalityManager.updatePersonality(current.copy(traits = adjusted))
                Log.d(TAG, "纠正分析驱动人格调整: $dimension ${formatDelta(delta)}")
            }
        }

        // 标记所有已分析
        correctionDao.markAllApplied()
    }

    /**
     * 分析纠正记录的共性模式
     *
     * 返回维度→调整值的映射
     * - 用户频繁纠正"太生硬" → agreeableness +0.02
     * - 用户频繁纠正"太啰嗦" → conscientiousness -0.02（更简洁灵活）
     * - 用户频繁纠正"不够具体" → conscientiousness +0.02
     * - 用户频繁纠正"太冷冰冰" → agreeableness +0.02, extraversion +0.01
     */
    private fun analyzePatterns(corrections: List<CorrectionEntity>): Map<String, Double> {
        val patternMap = mutableMapOf<String, Double>()

        val allCorrections = corrections.joinToString(" ") { it.correction.lowercase() }
        val allOriginals = corrections.joinToString(" ") { it.originalResponse.lowercase() }

        // 纠正"太生硬/太冷"
        val coldCount = Regex("太冷|生硬|不自然|太正经|太官方|温柔|友善")
            .findAll(allCorrections).count()
        if (coldCount >= 2) {
            patternMap["agreeableness"] = 0.02
            patternMap["extraversion"] = 0.01
        }

        // 纠正"太啰嗦/太冗长"
        val verboseCount = Regex("太啰嗦|太长了|简明|扼要|不用解释|简单点|直接|太长")
            .findAll(allCorrections).count()
        if (verboseCount >= 2) {
            patternMap["conscientiousness"] = -0.02
        }

        // 纠正"不够具体/不够详细"
        val detailCount = Regex("不够具体|不够详细|再详细|多说|展开|更清楚")
            .findAll(allCorrections).count()
        if (detailCount >= 2) {
            patternMap["conscientiousness"] = 0.02
        }

        // 纠正"太激进/太冒险"
        val aggressiveCount = Regex("太冒险|太激进|不安全|保守|谨慎")
            .findAll(allCorrections).count()
        if (aggressiveCount >= 2) {
            patternMap["openness"] = -0.02
        }

        return patternMap
    }

    private fun formatDelta(d: Double): String = String.format("%+.4f", d)
}
