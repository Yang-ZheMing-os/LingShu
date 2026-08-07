package com.lingshu.agent.feature.persona

import android.util.Log
import com.lingshu.agent.core.model.BigFiveTraits
import com.lingshu.agent.core.model.Persona
import com.lingshu.agent.core.model.PersonaRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * 人格管理器
 *
 * 职责：
 * 1. 当前激活人格的切换和内存缓存（避免每次读数据库）
 * 2. 生成用于大模型的 System Prompt（整合人格维度 + 记忆 + 规则 + 用户上下文）
 * 3. 人格切换时的钩子回调（用于通知其他模块）
 */
@Singleton
class PersonaManager @Inject constructor(
    private val repository: PersonaRepository
) {

    companion object {
        private const val TAG = "PersonaManager"

        /** System Prompt 中记忆展示的最大条数 */
        private const val PROMPT_MEMORY_LIMIT = 20

        /** 大五人格维度在 Prompt 中的描述权重 */
        private const val TRAIT_HIGH_THRESHOLD = 0.75  // 高于此值视为"高"
        private const val TRAIT_LOW_THRESHOLD = 0.25   // 低于此值视为"低"
    }

    // ==================== 内部协程作用域 ====================

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    // ==================== 激活人格缓存 ====================

    /**
     * 当前激活人格的内存缓存（StateFlow）
     * 与数据库中的 isActive 字段保持同步
     */
    private val _activePersona = MutableStateFlow<Persona?>(null)
    val activePersona: StateFlow<Persona?> = _activePersona.asStateFlow()

    /**
     * 当前激活人格ID的快速缓存（用于避免频繁读取Flow）
     */
    @Volatile
    private var cachedActiveId: String? = null

    /** 监听数据库中激活人格变化的Job */
    private var observeActiveJob: Job? = null

    init {
        // 启动时观察激活人格变化，自动更新缓存
        observeActiveJob = repository.observeActive()
            .onEach { persona ->
                mutex.withLock {
                    _activePersona.value = persona
                    cachedActiveId = persona?.personaId
                }
                Log.d(TAG, "激活人格缓存已更新: ${persona?.name} (${persona?.personaId})")
            }
            .launchIn(managerScope)

        // 预热：尝试立即加载一次激活人格
        managerScope.launch {
            val active = repository.getActiveOnce()
            if (active != null) {
                _activePersona.value = active
                cachedActiveId = active.personaId
            }
        }
    }

    // ==================== 激活人格查询/切换 ====================

    /**
     * 获取当前激活人格
     * 优先返回内存缓存，无缓存时尝试从数据库读取
     */
    fun getActivePersona(): Persona? {
        // 先尝试内存缓存
        _activePersona.value?.let { return it }

        // 无缓存时使用已缓存的ID做二次确认
        cachedActiveId?.let { id ->
            // 此处避免阻塞，调用方如需要同步数据应使用 suspend 版本
        }

        return null
    }

    /**
     * 获取当前激活人格（挂起版本，确保从数据库刷新）
     */
    suspend fun getActivePersonaSuspend(): Persona? {
        _activePersona.value?.let { return it }
        return withContext(Dispatchers.IO) {
            val active = repository.getActiveOnce()
            active?.let {
                _activePersona.value = it
                cachedActiveId = it.personaId
            }
            active
        }
    }

    /**
     * 切换激活人格
     *
     * @param id 目标人格ID
     * @return 是否切换成功（ID不存在或系统人格切换失败时返回false）
     */
    fun setActivePersona(id: String): Boolean {
        var success = false
        managerScope.launch {
            success = setActivePersonaSuspend(id)
        }
        // 此处返回值可能存在竞态，如果需要可靠结果请使用 suspend 版本
        return success
    }

    /**
     * 切换激活人格（挂起版本，保证完成）
     */
    suspend fun setActivePersonaSuspend(id: String): Boolean {
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                // 检查目标人格是否存在
                val target = repository.getById(id)
                if (target == null) {
                    Log.w(TAG, "切换人格失败：ID不存在 $id")
                    return@withContext false
                }

                // 如果已是当前激活人格，直接返回
                if (cachedActiveId == id) {
                    Log.d(TAG, "目标人格已是激活状态，跳过切换: $id")
                    return@withContext true
                }

                // 调用Repository更新数据库（会自动取消其他人格激活状态）
                val dbSuccess = repository.setActivePersona(id)
                if (dbSuccess) {
                    // 手动更新缓存（减少等待观察流的延迟）
                    _activePersona.value = target.copy(isActive = true)
                    cachedActiveId = id
                    Log.i(TAG, "人格切换成功: ${target.name} ($id)")
                }
                dbSuccess
            }
        }
    }

    // ==================== System Prompt 构建 ====================

    /**
     * 构建用于大模型的 System Prompt
     *
     * Prompt 结构（按优先级排列）：
     * 1. 角色身份（人格名称、开场白提示）
     * 2. 大五人格维度描述（自然语言，非数值）
     * 3. 人格规则（行为约束）
     * 4. 关于用户的记忆（从记忆库提取）
     * 5. 用户上下文（本次对话附带的动态信息：时间、地点、场景等）
     * 6. 语气风格标签
     * 7. 示例对话（Few-shot）
     * 8. 人格自定义 System Prompt（用户手动填写）
     *
     * @param active 当前激活人格
     * @param userContext 动态上下文Map，可包含 time, location, mood, scenario 等
     * @return 拼接完成的 System Prompt 字符串
     */
    fun buildSystemPrompt(active: Persona, userContext: Map<String, Any>): String {
        val sb = StringBuilder()

        // ========== 1. 角色身份 ==========
        sb.appendLine("# 角色设定")
        sb.appendLine("你是「${active.name}」，一位陪伴用户的AI助手。")
        active.openingLine?.let { line ->
            sb.appendLine("你的开场白风格参考：$line")
        }
        sb.appendLine()

        // ========== 2. 大五人格维度描述 ==========
        val traitDescription = describeBigFiveTraits(active.traits)
        if (traitDescription.isNotBlank()) {
            sb.appendLine("# 性格特征")
            sb.appendLine(traitDescription)
            sb.appendLine()
        }

        // ========== 3. 人格规则 ==========
        val rulesDescription = describePersonaRules(active.rules)
        if (rulesDescription.isNotBlank()) {
            sb.appendLine("# 行为规则")
            sb.appendLine(rulesDescription)
            sb.appendLine()
        }

        // ========== 4. 关于用户的记忆 ==========
        val recentMemories = active.memory.takeLast(PROMPT_MEMORY_LIMIT)
        if (recentMemories.isNotEmpty()) {
            sb.appendLine("# 关于用户的记忆（仅供参考，不要在对话中主动逐条列举）")
            recentMemories.forEachIndexed { idx, mem ->
                sb.appendLine("${idx + 1}. $mem")
            }
            sb.appendLine()
        }

        // ========== 5. 用户动态上下文 ==========
        if (userContext.isNotEmpty()) {
            sb.appendLine("# 当前对话上下文")
            userContext.forEach { (key, value) ->
                val readableKey = when (key) {
                    "time", "currentTime" -> "当前时间"
                    "date" -> "当前日期"
                    "location", "place" -> "当前地点"
                    "mood", "emotion" -> "用户情绪"
                    "scenario", "scene" -> "当前场景"
                    "weather" -> "天气情况"
                    "conversationTopic" -> "对话主题"
                    "healthData" -> "健康数据摘要"
                    "deviceStatus" -> "设备状态"
                    else -> key
                }
                sb.appendLine("- $readableKey：$value")
            }
            sb.appendLine()
        }

        // ========== 6. 语气风格标签 ==========
        if (active.toneTags.isNotEmpty()) {
            sb.appendLine("# 语气风格")
            sb.appendLine("请在对话中体现以下语气标签：${active.toneTags.joinToString("、")}")
            sb.appendLine()
        }

        // ========== 7. 示例对话（Few-shot） ==========
        if (active.exampleDialogues.isNotEmpty()) {
            sb.appendLine("# 对话风格示例（请模仿此风格进行回复）")
            active.exampleDialogues.forEachIndexed { idx, (user, assistant) ->
                sb.appendLine("示例${idx + 1}：")
                sb.appendLine("用户：$user")
                sb.appendLine("你：$assistant")
            }
            sb.appendLine()
        }

        // ========== 8. 用户自定义 System Prompt（追加在最后，优先级最高） ==========
        if (active.systemPrompt.isNotBlank()) {
            sb.appendLine("# 附加指令（最高优先级）")
            sb.appendLine(active.systemPrompt)
            sb.appendLine()
        }

        // 最后添加一条总括性指导
        sb.appendLine("# 综合指引")
        sb.appendLine("请始终基于以上设定进行回复，保持角色的一致性和连贯性。")
        sb.appendLine("回复要自然流畅，不要机械地复述设定内容，而是将设定融入你的表达风格中。")

        return sb.toString().trim()
    }

    /**
     * 使用当前激活人格构建 System Prompt
     * 如果没有激活人格，使用默认中立设定
     */
    suspend fun buildSystemPromptForActive(userContext: Map<String, Any> = emptyMap()): String {
        val active = getActivePersonaSuspend() ?: createDefaultPersona()
        return buildSystemPrompt(active, userContext)
    }

    /**
     * 将大五人格数值转换为自然语言描述
     * 仅描述明显偏高或偏低的维度，避免信息过载
     */
    private fun describeBigFiveTraits(traits: BigFiveTraits): String {
        val descriptions = mutableListOf<String>()

        // 开放性（Openness）：高=好奇/创新/艺术，低=务实/传统/保守
        descriptions.add(
            describeTrait(
                value = traits.openness,
                name = "开放性",
                highDesc = "对新事物充满好奇，喜欢探索创意和抽象概念，乐于尝试不同的思路和方法",
                lowDesc = "偏好实际和具体的事物，倾向于遵循传统和常规，重视稳定和实用性"
            )
        )

        // 尽责性（Conscientiousness）：高=有条理/自律/可靠，低=灵活/随性/不拘小节
        descriptions.add(
            describeTrait(
                value = traits.conscientiousness,
                name = "尽责性",
                highDesc = "做事有条理、计划性强，重视细节和质量，言出必行，值得信赖",
                lowDesc = "随性灵活，不拘泥于计划和规则，适应变化能力强，不喜繁文缛节"
            )
        )

        // 外向性（Extraversion）：高=热情/社交/活跃，低=内敛/独立/深思
        descriptions.add(
            describeTrait(
                value = traits.extraversion,
                name = "外向性",
                highDesc = "热情开朗，喜欢与人互动交流，善于活跃气氛，表达直接明快",
                lowDesc = "内敛含蓄，偏好深度交流而非广泛社交，善于倾听和独立思考"
            )
        )

        // 宜人性（Agreeableness）：高=友善/合作/同理心，低=直接/批判/独立
        descriptions.add(
            describeTrait(
                value = traits.agreeableness,
                name = "宜人性",
                highDesc = "友善随和，富有同理心，乐于帮助他人，重视和谐与合作，避免冲突",
                lowDesc = "表达直接坦诚，不轻易妥协，有独立见解，擅长分析和批判性思考"
            )
        )

        // 神经质（Neuroticism）：高=敏感/情绪多变/细腻，低=稳定/冷静/抗压
        descriptions.add(
            describeTrait(
                value = traits.neuroticism,
                name = "情绪稳定性",
                highDesc = "情感细腻敏锐，对环境和他人情绪变化感知强烈，共情力极强",
                lowDesc = "情绪稳定从容，抗压能力强，面对困难冷静理性，不易焦虑或动摇"
            )
        )

        // 过滤掉"中性"描述，只保留有明显倾向的
        val nonEmpty = descriptions.filter { it.isNotBlank() }

        // 添加一个综合的性格强度总结
        if (nonEmpty.isNotEmpty()) {
            val summary = buildTraitSummary(traits)
            return buildString {
                appendLine("整体性格画像：$summary")
                nonEmpty.forEachIndexed { idx, desc ->
                    append("${idx + 1}. $desc")
                    if (idx < nonEmpty.size - 1) appendLine()
                }
            }
        }

        // 完全中性的情况
        return "性格较为平衡温和，各维度特征不明显，可以根据对话情境灵活调整沟通风格。"
    }

    /**
     * 描述单个人格维度的倾向（极高/高/中/低/极低）
     * 处于中间区间（0.25~0.75）时返回空字符串，避免过多细节
     */
    private fun describeTrait(
        value: Double,
        name: String,
        highDesc: String,
        lowDesc: String
    ): String {
        return when {
            value >= TRAIT_HIGH_THRESHOLD -> {
                val intensity = if (value >= 0.9) "极强" else if (value >= 0.85) "很高" else "偏高"
                "$name$intensity：$highDesc"
            }
            value <= TRAIT_LOW_THRESHOLD -> {
                val intensity = if (value <= 0.1) "极低" else if (value <= 0.15) "很低" else "偏低"
                "$name$intensity：$lowDesc"
            }
            else -> ""  // 中性区间不描述
        }
    }

    /**
     * 生成性格综合总结（一句话画像）
     */
    private fun buildTraitSummary(traits: BigFiveTraits): String {
        val adjectives = mutableListOf<String>()

        // 根据显著维度挑选形容词
        if (traits.openness >= TRAIT_HIGH_THRESHOLD) adjectives.add("富有创意的")
        else if (traits.openness <= TRAIT_LOW_THRESHOLD) adjectives.add("务实可靠的")

        if (traits.conscientiousness >= TRAIT_HIGH_THRESHOLD) adjectives.add("严谨认真的")
        else if (traits.conscientiousness <= TRAIT_LOW_THRESHOLD) adjectives.add("洒脱随性的")

        if (traits.extraversion >= TRAIT_HIGH_THRESHOLD) adjectives.add("热情外向的")
        else if (traits.extraversion <= TRAIT_LOW_THRESHOLD) adjectives.add("内敛沉稳的")

        if (traits.agreeableness >= TRAIT_HIGH_THRESHOLD) adjectives.add("温暖友善的")
        else if (traits.agreeableness <= TRAIT_LOW_THRESHOLD) adjectives.add("率直独立的")

        if (traits.neuroticism >= TRAIT_HIGH_THRESHOLD) adjectives.add("细腻敏感的")
        else if (traits.neuroticism <= TRAIT_LOW_THRESHOLD) adjectives.add("从容镇定的")

        return if (adjectives.isNotEmpty()) {
            adjectives.take(3).joinToString("、") + "个性"
        } else {
            "温和平衡的个性"
        }
    }

    /**
     * 将人格规则（布尔开关）转换为自然语言描述
     */
    private fun describePersonaRules(rules: PersonaRules): String {
        val rulesDesc = mutableListOf<String>()

        rulesDesc.add(
            if (rules.canInitiateConversation)
                "可以主动发起对话（当检测到合适时机时可主动联系用户）"
            else
                "禁止主动发起对话（只能在用户先开口后回复）"
        )

        rulesDesc.add(
            if (rules.confirmBeforeExecute)
                "执行设备操作前必须向用户确认，不能自作主张直接执行"
            else
                "对于常规操作可以直接执行，无需每次都向用户确认"
        )

        rulesDesc.add(
            if (rules.canUseSensitiveOperations)
                "允许在必要时使用敏感操作（如发送消息、转账、卸载应用等），但仍需谨慎"
            else
                "严格禁止执行敏感操作（发送消息、支付、卸载应用等），遇到此类请求必须拒绝"
        )

        rulesDesc.add(
            if (rules.canAccessInternet)
                "可以使用互联网搜索功能获取实时信息来辅助回答"
            else
                "禁止访问互联网，只能依靠已有知识和本地数据进行回答"
        )

        return rulesDesc.mapIndexed { idx, desc -> "${idx + 1}. $desc" }
            .joinToString("\n")
    }

    // ==================== 辅助方法 ====================

    /**
     * 创建默认中立人格（当无激活人格时备用）
     */
    private fun createDefaultPersona(): Persona {
        return Persona(
            personaId = "default_fallback",
            name = "灵枢",
            traits = BigFiveTraits.neutral(),
            rules = PersonaRules(
                canInitiateConversation = true,
                confirmBeforeExecute = true,
                canUseSensitiveOperations = false,
                canAccessInternet = true
            ),
            toneTags = listOf("友善", "专业", "自然")
        )
    }

    /**
     * 获取大五人格维度的数值百分比（用于UI展示柱状图）
     */
    fun getTraitPercentages(traits: BigFiveTraits): Map<String, Int> {
        return mapOf(
            "开放性" to (traits.openness * 100).roundToInt(),
            "尽责性" to (traits.conscientiousness * 100).roundToInt(),
            "外向性" to (traits.extraversion * 100).roundToInt(),
            "宜人性" to (traits.agreeableness * 100).roundToInt(),
            "情绪稳定" to (1.0 - traits.neuroticism * 100).roundToInt()
        )
    }

    /**
     * 清除内存缓存并重新从数据库加载（用于数据导入后刷新）
     */
    suspend fun refreshCache() {
        mutex.withLock {
            val active = repository.getActiveOnce()
            _activePersona.value = active
            cachedActiveId = active?.personaId
        }
    }
}
