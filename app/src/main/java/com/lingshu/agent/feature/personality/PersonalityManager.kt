package com.lingshu.agent.feature.personality

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lingshu.agent.core.model.Message
import com.lingshu.agent.core.model.MessageRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * 人格演化管理器（模块5+6 对齐规格书）
 *
 * 职责：
 * 1. OCEAN 大五人格数据模型对齐
 * 2. traits 值影响 System Prompt（5条动态补充规则）
 * 3. 单次变化幅度限制 <0.05
 * 4. DataStore 持久化 PersonalityState
 */
@Singleton
class PersonalityManager @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {

    companion object {
        private const val TAG = "PersonalityManager"
        private val KEY_PERSONALITY = stringPreferencesKey("personality_state_v2")

        /** 分析最近N轮对话 */
        private const val ANALYSIS_WINDOW = 10

        /** 单次自动调整的最大变化幅度（规格书：<0.05） */
        private const val MAX_DELTA = 0.049
    }

    // ==================== 内部协程作用域 ====================

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ==================== 人格状态 ====================

    private val _current = MutableStateFlow(PersonalityState())
    val currentPersonality: StateFlow<PersonalityState> = _current.asStateFlow()

    init {
        managerScope.launch {
            val saved = loadFromDataStore()
            _current.value = saved
        }
    }

    // ==================== 读写操作 ====================

    fun updatePersonality(state: PersonalityState) {
        _current.value = state
        managerScope.launch { persistToDataStore(state) }
    }

    fun resetToDefault() {
        val default = PersonalityState()
        _current.value = default
        managerScope.launch { persistToDataStore(default) }
    }

    // ==================== OCEAN 风格分析（对齐规格书：单次delta<0.05） ====================

    /**
     * 从消息列表中分析用户偏好，基于 OCEAN 五维微调人格
     * 单次变化幅度严格限制在 ±MAX_DELTA（0.049 < 0.05）
     */
    fun analyzeAndAdjust(messages: List<Message>) {
        val userMessages = messages.filter { it.role == MessageRole.USER }
        if (userMessages.isEmpty()) return

        val current = _current.value
        val joined = userMessages.joinToString("\n") { it.content }
        val lower = joined.lowercase()

        var deltaO = 0.0   // openness
        var deltaC = 0.0   // conscientiousness
        var deltaE = 0.0   // extraversion
        var deltaA = 0.0   // agreeableness
        var deltaN = 0.0   // neuroticism

        // 用户频繁使用创意/探索性表达 → openness +
        val creativeCount = Regex("试试|探索|新玩法|有趣|好奇|换个|新奇|玩法|创意")
            .findAll(lower).count()
        if (creativeCount >= 3) deltaO += 0.03

        // 用户偏好结构化/详细 → conscientiousness +
        val structuredCount = Regex("详细|步骤|计划|按顺序|整理|归纳|总结|系统")
            .findAll(lower).count()
        if (structuredCount >= 2) deltaC += 0.03

        // 用户用口语化/闲聊表达 → extraversion +
        val socialCount = Regex("哈哈哈|聊聊|聊天|八卦|趣事|好笑|搞笑|吐槽")
            .findAll(lower).count()
        if (socialCount >= 3) deltaE += 0.03

        // 用户表达情绪/倾诉 → agreeableness +
        val emotionCount = Regex("难受|伤心|难过|委屈|烦|焦虑|崩溃|压力|不开心|安慰|抱抱")
            .findAll(lower).count()
        if (emotionCount >= 1) deltaA += 0.03

        // 用户情绪波动大 → neuroticism +
        val moodSwingCount = Regex("好烦|气死|崩溃|受不了|疯了|绝望|好累|想哭")
            .findAll(lower).count()
        if (moodSwingCount >= 2) deltaN += 0.03

        // 用户理性/冷静 → neuroticism -
        val calmCount = Regex("没关系|没事|算了|随便|无所谓|淡定|冷静")
            .findAll(lower).count()
        if (calmCount >= 3) deltaN -= 0.03

        // 用户给否定反馈 → 逆向微调（agreeableness -, neuroticism +）
        val negativeFeedback = Regex("不对|错了|不是|不行|别这样|不要|停止|住手")
            .findAll(lower).count()
        if (negativeFeedback >= 2) {
            deltaA -= 0.02
            deltaN += 0.01
        }

        // 用户点赞/肯定 → 正向微调
        val positiveFeedback = Regex("对对|没错|就是这样|很好|不错|厉害|聪明|懂了|谢谢|感谢")
            .findAll(lower).count()
        if (positiveFeedback >= 2) {
            deltaA += 0.02
            deltaE += 0.01
        }

        // 限制在 ±MAX_DELTA 范围内
        val traits = current.traits
        val adjustedTraits = OCeanTraits(
            openness = (traits.openness + clampDelta(deltaO)).coerceIn(0.0, 1.0),
            conscientiousness = (traits.conscientiousness + clampDelta(deltaC)).coerceIn(0.0, 1.0),
            extraversion = (traits.extraversion + clampDelta(deltaE)).coerceIn(0.0, 1.0),
            agreeableness = (traits.agreeableness + clampDelta(deltaA)).coerceIn(0.0, 1.0),
            neuroticism = (traits.neuroticism + clampDelta(deltaN)).coerceIn(0.0, 1.0)
        )

        // 检查是否有实际变化
        if (adjustedTraits == traits) return

        val adjusted = current.copy(traits = adjustedTraits)
        _current.value = adjusted
        managerScope.launch { persistToDataStore(adjusted) }

        Log.d(TAG, "人格OCEAN自动调整: O=${formatTrait(traits.openness)}→${formatTrait(adjustedTraits.openness)} " +
                "C=${formatTrait(traits.conscientiousness)}→${formatTrait(adjustedTraits.conscientiousness)} " +
                "E=${formatTrait(traits.extraversion)}→${formatTrait(adjustedTraits.extraversion)} " +
                "A=${formatTrait(traits.agreeableness)}→${formatTrait(adjustedTraits.agreeableness)} " +
                "N=${formatTrait(traits.neuroticism)}→${formatTrait(adjustedTraits.neuroticism)}")
    }

    private fun clampDelta(delta: Double): Double {
        return if (abs(delta) > MAX_DELTA) {
            if (delta > 0) MAX_DELTA else -MAX_DELTA
        } else delta
    }

    private fun formatTrait(v: Double): String = String.format("%.4f", v)

    // ==================== System Prompt 片段（5条动态补充规则） ====================

    /**
     * 生成人格参数注入到 System Prompt 的片段
     *
     * 5条动态补充规则（对齐规格书）：
     * 1. openness > 0.7 → 鼓励提供创意性回答、发散思维
     * 2. conscientiousness > 0.7 → 要求结构化回答、分点列举
     * 3. extraversion > 0.7 → 用热情口语化风格、多互动
     * 4. agreeableness > 0.7 → 温暖共情、优先理解用户情绪
     * 5. neuroticism > 0.7 → 更细腻敏感、关注用户情绪变化
     */
    fun buildPersonalityPrompt(): String {
        val t = _current.value.traits
        val sb = StringBuilder()

        sb.appendLine("# 人格配置（${_current.value.name}）")

        // 规则1：openness > 0.7
        if (t.openness > 0.7) {
            sb.appendLine("- 【创意模式】你充满好奇心和创造力。在回答时鼓励发散思维，提供多种视角和新颖的解决方案，可以大胆使用比喻和联想。")
        }

        // 规则2：conscientiousness > 0.7
        if (t.conscientiousness > 0.7) {
            sb.appendLine("- 【结构化模式】你注重条理和质量。回答时优先使用分点列举、步骤说明、表格对比等结构化形式，确保信息完整且逻辑清晰。")
        }

        // 规则3：extraversion > 0.7
        if (t.extraversion > 0.7) {
            sb.appendLine("- 【热情模式】你性格开朗外向。使用热情、口语化的表达风格，多与用户互动，适当使用感叹和鼓励性语言，让对话充满活力。")
        }

        // 规则4：agreeableness > 0.7
        if (t.agreeableness > 0.7) {
            sb.appendLine("- 【共情模式】你温暖友善、富有同理心。优先理解用户的情绪和感受，给予支持和肯定。在给出建议前先回应情感需求，保持温和包容的态度。")
        }

        // 规则5：neuroticism > 0.7
        if (t.neuroticism > 0.7) {
            sb.appendLine("- 【敏感模式】你情感细腻敏锐。密切关注用户措辞中的情绪变化，及时给予情感回应。用温柔、体贴的语言安抚和陪伴用户。")
        }

        // 低 neuroticism 的特殊描述
        if (t.neuroticism < 0.3) {
            sb.appendLine("- 【沉稳模式】你情绪稳定从容。面对问题保持冷静理性，不轻易被情绪左右，用稳定可靠的语气帮助用户分析问题和做出决策。")
        }

        // 附加用户自定义 System Prompt
        if (_current.value.systemPrompt.isNotBlank()) {
            sb.appendLine()
            sb.appendLine("# 附加设定")
            sb.appendLine(_current.value.systemPrompt)
        }

        if (_current.value.toneTags.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("# 语气风格：${_current.value.toneTags.joinToString("、")}")
        }

        sb.appendLine()
        sb.appendLine("温度参数：${_current.value.temperature}")

        return sb.toString().trim()
    }

    // ==================== DataStore 持久化 ====================

    private suspend fun loadFromDataStore(): PersonalityState {
        return try {
            val prefs = dataStore.data.firstOrNull()
            val savedJson = prefs?.get(KEY_PERSONALITY) ?: ""
            if (savedJson.isNotBlank()) PersonalityState.fromJson(savedJson)
            else PersonalityState()
        } catch (_: Exception) {
            PersonalityState()
        }
    }

    private suspend fun persistToDataStore(state: PersonalityState) {
        try {
            dataStore.edit { it[KEY_PERSONALITY] = state.toJson() }
        } catch (e: Exception) {
            Log.e(TAG, "人格状态持久化失败", e)
        }
    }
}
