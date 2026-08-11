package com.lingshu.agent.feature.persona

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lingshu.agent.core.database.Converters
import com.lingshu.agent.core.database.dao.PersonaDao
import com.lingshu.agent.core.database.entity.PersonaEntity
import com.lingshu.agent.core.model.BigFiveTraits
import com.lingshu.agent.core.model.Message
import com.lingshu.agent.core.model.Persona
import com.lingshu.agent.core.model.PersonaRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.exp

/**
 * 人格数据仓库
 *
 * 职责：
 * 1. 人格的CRUD操作（通过Room持久化）
 * 2. 人格导入/导出（JSON格式）
 * 3. 大五人格维度微调算法（基于用户反馈的演化）
 * 4. 从对话历史自动提取记忆注入人格
 * 5. 提供响应式数据流（Flow）
 */
@Singleton
class PersonaRepository @Inject constructor(
    private val personaDao: PersonaDao,
    private val gson: Gson
) {

    companion object {
        private const val TAG = "PersonaRepository"

        /** 人格演化最小调整步长 */
        private const val MIN_EVOLVE_RATE = 0.01

        /** 人格演化最大调整步长 */
        private const val MAX_EVOLVE_RATE = 0.05

        /** 演化冷却时间（毫秒）- 同一维度在此时间内不重复演化 */
        private const val EVOLVE_COOLDOWN_MS = 30_000L // 30秒

        /** 记忆最大条数 */
        private const val MAX_MEMORY_COUNT = 100

        /** 记忆提取关键词阈值 */
        private const val MEMORY_KEYWORD_THRESHOLD = 2
    }

    // ==================== 演化冷却追踪 ====================

    /**
     * 维度演化冷却记录
     * Key: personaId + 维度名
     * Value: 上次演化时间戳
     */
    private val evolveCooldownMap = ConcurrentHashMap<String, Long>()

    /**
     * 人格演化计数（用于渐进式降低调整幅度）
     * Key: personaId
     * Value: 累计演化次数
     */
    private val evolveCountMap = ConcurrentHashMap<String, Int>()

    // ==================== Entity <-> Model 转换 ====================

    private val converters = Converters()

    private fun PersonaEntity.toModel(): Persona {
        return Persona(
            personaId = id,
            name = name,
            avatar = avatarUrl,
            systemPrompt = systemPrompt,
            traits = BigFiveTraits(
                openness = traitsOpenness,
                conscientiousness = traitsConscientiousness,
                extraversion = traitsExtraversion,
                agreeableness = traitsAgreeableness,
                neuroticism = traitsNeuroticism
            ),
            voiceId = voiceId,
            temperature = temperature,
            memory = converters.toStringList(memory),
            openingLine = null,
            exampleDialogues = converters.toPairStringList(exampleDialogues),
            tags = converters.toStringList(tags),
            toneTags = emptyList(),
            rules = converters.toPersonaRules(rules),
            isActive = isActive,
            isSystem = isDefault,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    private fun Persona.toEntity(): PersonaEntity {
        return PersonaEntity(
            id = personaId,
            name = name,
            avatarUrl = avatar,
            systemPrompt = systemPrompt,
            traitsOpenness = traits.openness,
            traitsConscientiousness = traits.conscientiousness,
            traitsExtraversion = traits.extraversion,
            traitsAgreeableness = traits.agreeableness,
            traitsNeuroticism = traits.neuroticism,
            voiceId = voiceId,
            temperature = temperature,
            memory = converters.fromStringList(memory),
            exampleDialogues = converters.fromPairStringList(exampleDialogues),
            tags = converters.fromStringList(tags),
            rules = converters.fromPersonaRules(rules),
            isActive = isActive,
            isDefault = isSystem,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    // ==================== CRUD 操作 ====================

    /**
     * 观察所有人格列表（响应式）
     * 按激活状态优先，再按更新时间倒序
     */
    fun observeAll(): Flow<List<Persona>> {
        return personaDao.observeAll().map { entities ->
            entities.map { it.toModel() }
        }
    }

    /**
     * 观察当前激活的人格
     */
    fun observeActive(): Flow<Persona?> {
        return personaDao.observeActive().map { it?.toModel() }
    }

    /**
     * 观察系统人格或自定义人格列表
     */
    fun observeBySystem(isSystem: Boolean): Flow<List<Persona>> {
        return personaDao.observeBySystem(isSystem).map { entities ->
            entities.map { it.toModel() }
        }
    }

    /**
     * 搜索人格（按标签、名称、描述）
     */
    fun search(keyword: String, tag: String = ""): Flow<List<Persona>> {
        return personaDao.search(tag, keyword).map { entities ->
            entities.map { it.toModel() }
        }
    }

    /**
     * 根据ID获取人格（一次性获取，非Flow）
     */
    suspend fun getById(personaId: String): Persona? {
        return personaDao.getById(personaId)?.toModel()
    }

    /**
     * 获取当前激活人格（一次性获取）
     */
    suspend fun getActiveOnce(): Persona? {
        return personaDao.getActiveSuspend()?.toModel()
    }

    /**
     * 插入或更新人格
     */
    suspend fun upsert(persona: Persona) {
        val entity = persona.copy(updatedAt = System.currentTimeMillis()).toEntity()
        personaDao.upsert(entity)
    }

    /**
     * 批量插入人格
     */
    suspend fun upsertAll(personas: List<Persona>) {
        val now = System.currentTimeMillis()
        val entities = personas.map { it.copy(updatedAt = now).toEntity() }
        personaDao.upsertAll(entities)
    }

    /**
     * 删除人格
     * @return 是否删除成功（系统人格无法删除）
     */
    suspend fun delete(personaId: String): Boolean {
        val persona = getById(personaId) ?: return false
        if (persona.isSystem) {
            Log.w(TAG, "无法删除系统人格: $personaId")
            return false
        }
        personaDao.delete(personaId)
        evolveCooldownMap.entries.removeIf { it.key.startsWith(personaId) }
        evolveCountMap.remove(personaId)
        return true
    }

    /**
     * 设置激活人格
     * 会自动取消其他人格的激活状态
     */
    suspend fun setActivePersona(personaId: String): Boolean {
        val exists = getById(personaId) != null
        if (!exists) return false
        personaDao.setActive(personaId)
        return true
    }

    // ==================== 人格导入/导出 ====================

    /**
     * 导出人格为JSON字符串
     * 包含完整的人格配置，可用于分享和备份
     */
    fun exportToJson(persona: Persona): String {
        val exportData = PersonaExportData(
            version = 1,
            exportTime = System.currentTimeMillis(),
            persona = persona
        )
        return gson.toJson(exportData)
    }

    /**
     * 批量导出人格为JSON
     */
    fun exportAllToJson(personas: List<Persona>): String {
        val exportData = PersonaListExportData(
            version = 1,
            exportTime = System.currentTimeMillis(),
            personas = personas
        )
        return gson.toJson(exportData)
    }

    /**
     * 从JSON导入人格
     * @param json JSON字符串
     * @param overwriteId 是否覆盖原有ID（false则生成新ID，避免冲突）
     * @return 导入的人格对象，失败返回null
     */
    fun importFromJson(json: String, overwriteId: Boolean = false): Persona? {
        return try {
            val exportData = gson.fromJson(json, PersonaExportData::class.java)
            val persona = exportData.persona
            if (overwriteId) {
                persona.copy(
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    isActive = false,
                    isSystem = false
                )
            } else {
                persona.copy(
                    personaId = System.currentTimeMillis().toString(),
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    isActive = false,
                    isSystem = false
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "导入人格JSON失败: ${e.message}", e)
            null
        }
    }

    /**
     * 从JSON批量导入人格
     */
    fun importListFromJson(json: String, overwriteId: Boolean = false): List<Persona> {
        return try {
            // 先尝试解析为列表格式
            val listType = object : TypeToken<PersonaListExportData>() {}.type
            val listData = gson.fromJson<PersonaListExportData>(json, listType)
            listData.personas.map { persona ->
                if (overwriteId) {
                    persona.copy(
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                        isActive = false,
                        isSystem = false
                    )
                } else {
                    persona.copy(
                        personaId = "${System.currentTimeMillis()}_${(0..9999).random()}",
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                        isActive = false,
                        isSystem = false
                    )
                }
            }
        } catch (e: Exception) {
            // 列表解析失败，尝试单个人格
            val single = importFromJson(json, overwriteId)
            listOfNotNull(single)
        }
    }

    // ==================== 大五人格演化算法 ====================

    /**
     * 根据用户反馈和情感状态演化人格维度
     *
     * 算法原理：
     * 1. 反馈极性：LIKED 正向强化，DISLIKED 反向弱化
     * 2. 情感修正：根据emotion关键词对特定维度施加额外偏移
     * 3. 冷却机制：同一维度30秒内不重复演化，防止抖动
     * 4. 衰减机制：演化次数越多，调整幅度越小（防止过拟合）
     * 5. 边界夹紧：所有维度强制约束在 [0.0, 1.0]
     *
     * @param personaId 目标人格ID
     * @param feedback 用户反馈（点赞/点踩）
     * @param emotion 情感标签（可选，如"happy","sad","angry"等）
     */
    suspend fun evolvePersona(
        personaId: String,
        feedback: Message.Feedback,
        emotion: String?
    ) {
        val persona = getById(personaId) ?: run {
            Log.w(TAG, "演化失败：人格不存在 $personaId")
            return
        }

        val now = System.currentTimeMillis()
        val baseDirection = if (feedback == Message.Feedback.LIKED) 1.0 else -1.0

        // 计算演化次数衰减系数（指数衰减，次数越多幅度越小）
        val evolveCount = evolveCountMap.getOrDefault(personaId, 0)
        val decayFactor = exp(-evolveCount * 0.02) // 每50次衰减约63%

        // 基础调整幅度（考虑衰减，约束在 MIN~MAX 之间）
        val baseRate = (MAX_EVOLVE_RATE * decayFactor).coerceIn(MIN_EVOLVE_RATE, MAX_EVOLVE_RATE)

        // 计算各维度的调整增量
        var delta = computeFeedbackDelta(feedback, emotion)

        // 应用冷却检查，跳过仍在冷却期的维度
        val traitNames = listOf("openness", "conscientiousness", "extraversion", "agreeableness", "neuroticism")
        val deltaValues = mutableListOf(
            delta.openness,
            delta.conscientiousness,
            delta.extraversion,
            delta.agreeableness,
            delta.neuroticism
        )

        traitNames.forEachIndexed { index, traitName ->
            val coolKey = "${personaId}_$traitName"
            val lastEvolve = evolveCooldownMap.getOrDefault(coolKey, 0L)
            val inCooldown = (now - lastEvolve) < EVOLVE_COOLDOWN_MS
            val isZero = abs(deltaValues[index]) < 0.001

            if (inCooldown || isZero) {
                deltaValues[index] = 0.0
            } else {
                // 应用方向和速率
                deltaValues[index] *= baseDirection * baseRate
                evolveCooldownMap[coolKey] = now
            }
        }

        // 构建调整后的BigFiveTraits
        val adjustedDelta = BigFiveTraits(
            openness = deltaValues[0],
            conscientiousness = deltaValues[1],
            extraversion = deltaValues[2],
            agreeableness = deltaValues[3],
            neuroticism = deltaValues[4]
        )

        // 应用调整并夹紧到 [0,1]
        val newTraits = BigFiveTraits(
            openness = (persona.traits.openness + adjustedDelta.openness).coerceIn(0.0, 1.0),
            conscientiousness = (persona.traits.conscientiousness + adjustedDelta.conscientiousness).coerceIn(0.0, 1.0),
            extraversion = (persona.traits.extraversion + adjustedDelta.extraversion).coerceIn(0.0, 1.0),
            agreeableness = (persona.traits.agreeableness + adjustedDelta.agreeableness).coerceIn(0.0, 1.0),
            neuroticism = (persona.traits.neuroticism + adjustedDelta.neuroticism).coerceIn(0.0, 1.0)
        )

        // 累加演化计数
        evolveCountMap[personaId] = evolveCount + 1

        // 持久化更新
        personaDao.updateTraits(personaId, newTraits.openness, newTraits.conscientiousness,
            newTraits.extraversion, newTraits.agreeableness, newTraits.neuroticism, now)

        Log.d(TAG, "人格演化完成 [$personaId] feedback=$feedback emotion=$emotion " +
                "rate=%.4f decay=%.3f count=%d".format(baseRate, decayFactor, evolveCount + 1))
    }

    /**
     * 根据反馈类型和情感标签计算初始维度偏移向量
     * 返回的是偏移方向（未乘以速率和方向）
     */
    private fun computeFeedbackDelta(
        feedback: Message.Feedback,
        emotion: String?
    ): BigFiveTraits {
        // 基础偏移：点赞时微调当前倾向，点踩时向中性回归
        val base = when (feedback) {
            Message.Feedback.LIKED -> BigFiveTraits(
                openness = 0.6,
                conscientiousness = 0.5,
                extraversion = 0.4,
                agreeableness = 0.7,
                neuroticism = -0.3
            )
            Message.Feedback.DISLIKED -> BigFiveTraits(
                openness = -0.4,
                conscientiousness = -0.3,
                extraversion = -0.5,
                agreeableness = -0.4,
                neuroticism = 0.4
            )
        }

        // 情感修正：叠加情感相关的维度偏移
        val emotionModifier = emotion?.lowercase()?.let { emo ->
            when {
                emo.contains("happy") || emo.contains("joy") || emo.contains("愉快") ->
                    BigFiveTraits(0.3, 0.1, 0.5, 0.4, -0.4)
                emo.contains("sad") || emo.contains("grief") || emo.contains("悲伤") ->
                    BigFiveTraits(-0.1, -0.2, -0.5, 0.1, 0.5)
                emo.contains("angry") || emo.contains("rage") || emo.contains("愤怒") ->
                    BigFiveTraits(0.1, -0.3, 0.2, -0.6, 0.6)
                emo.contains("fear") || emo.contains("anxious") || emo.contains("焦虑") ->
                    BigFiveTraits(-0.5, -0.1, -0.4, 0.0, 0.7)
                emo.contains("surprise") || emo.contains("惊讶") ->
                    BigFiveTraits(0.7, 0.0, 0.3, 0.1, 0.1)
                emo.contains("disgust") || emo.contains("厌恶") ->
                    BigFiveTraits(-0.3, 0.1, -0.2, -0.4, 0.2)
                emo.contains("love") || emo.contains("affection") || emo.contains("喜爱") ->
                    BigFiveTraits(0.2, 0.2, 0.3, 0.8, -0.3)
                emo.contains("bored") || emo.contains("无聊") ->
                    BigFiveTraits(-0.5, -0.3, -0.3, -0.1, 0.1)
                emo.contains("confused") || emo.contains("困惑") ->
                    BigFiveTraits(-0.2, -0.4, -0.1, 0.1, 0.2)
                emo.contains("proud") || emo.contains("自豪") ->
                    BigFiveTraits(0.2, 0.4, 0.5, -0.1, -0.2)
                else -> BigFiveTraits(0.0, 0.0, 0.0, 0.0, 0.0)
            }
        } ?: BigFiveTraits(0.0, 0.0, 0.0, 0.0, 0.0)

        // 合并：基础 70% + 情感修正 30%
        val weightBase = 0.7
        val weightEmotion = 0.3
        return BigFiveTraits(
            openness = base.openness * weightBase + emotionModifier.openness * weightEmotion,
            conscientiousness = base.conscientiousness * weightBase + emotionModifier.conscientiousness * weightEmotion,
            extraversion = base.extraversion * weightBase + emotionModifier.extraversion * weightEmotion,
            agreeableness = base.agreeableness * weightBase + emotionModifier.agreeableness * weightEmotion,
            neuroticism = base.neuroticism * weightBase + emotionModifier.neuroticism * weightEmotion
        )
    }

    /**
     * 重置某人格的演化历史（冷却和计数清零）
     */
    fun resetEvolveHistory(personaId: String) {
        evolveCooldownMap.entries.removeIf { it.key.startsWith(personaId) }
        evolveCountMap.remove(personaId)
    }

    // ==================== 记忆注入系统 ====================

    /**
     * 从对话历史中提取关键信息并注入人格记忆
     *
     * 提取规则：
     * 1. 包含"我叫/我是/我的名字"等自称 → 记录用户姓名/身份
     * 2. 包含"喜欢/爱/讨厌/害怕"等情感偏好词 → 记录喜好
     * 3. 包含具体日期（生日、纪念日等）→ 记录重要日期
     * 4. 包含"住在/来自/工作在"等地点信息 → 记录位置
     * 5. 包含职业、家人、宠物等个人信息 → 记录背景
     *
     * @param personaId 目标人格ID
     * @param userMessages 用户发送的消息列表
     * @return 实际新增的记忆条数
     */
    suspend fun injectMemoriesFromDialogue(
        personaId: String,
        userMessages: List<Message>
    ): Int {
        val persona = getById(personaId) ?: return 0
        val existingMemories = persona.memory.toMutableList()
        val newMemories = mutableListOf<String>()

        for (msg in userMessages) {
            if (!msg.isUserMessage()) continue
            val text = msg.content.trim()
            if (text.length < 4) continue

            val extracted = extractMemoryFromText(text)
            for (memory in extracted) {
                // 去重：相似度检查
                val isDuplicate = existingMemories.any { it == memory ||
                        similarity(it, memory) > 0.85 }
                if (!isDuplicate) {
                    newMemories.add(memory)
                    existingMemories.add(memory)
                }
            }
        }

        if (newMemories.isNotEmpty()) {
            // 限制最大记忆条数，超出则移除最旧的
            val trimmedMemories = if (existingMemories.size > MAX_MEMORY_COUNT) {
                existingMemories.takeLast(MAX_MEMORY_COUNT)
            } else {
                existingMemories
            }

            val memoryJson = converters.fromStringList(trimmedMemories)
            personaDao.updateMemory(personaId, memoryJson, System.currentTimeMillis())
            Log.d(TAG, "人格[$personaId] 注入 ${newMemories.size} 条新记忆")
        }

        return newMemories.size
    }

    /**
     * 从单条文本中提取潜在记忆点
     */
    private fun extractMemoryFromText(text: String): List<String> {
        val results = mutableListOf<String>()
        val lowerText = text.lowercase()

        // 用户自称/姓名
        val namePatterns = listOf(
            Regex("""我叫([^，。,.!?！？\s]{1,10})"""),
            Regex("""我是([^，。,.!?！？\s]{1,10})"""),
            Regex("""我的名字([是叫])?([^，。,.!?！？\s]{1,10})"""),
            Regex("""大[家家]可以叫[我做]([^，。,.!?！？\s]{1,10})""")
        )
        namePatterns.forEach { pattern ->
            pattern.find(text)?.groupValues?.getOrNull(1)?.let { name ->
                if (name.length in 2..10) {
                    results.add("用户名字：$name")
                }
            }
        }

        // 喜好类：喜欢/爱 + 名词
        val likePatterns = listOf(
            Regex("""我[喜欢愛吃喝玩看听]([^，。,.!?！？]{1,30})"""),
            Regex("""我[最挺特别非常很]?喜欢([^，。,.!?！？]{1,30})"""),
            Regex("""我的爱[好号]是([^，。,.!?！？]{1,30})""")
        )
        likePatterns.forEach { pattern ->
            pattern.find(text)?.groupValues?.getOrNull(1)?.let { thing ->
                val trimmed = thing.trim()
                if (trimmed.length in 2..30) {
                    results.add("用户喜欢：$trimmed")
                }
            }
        }

        // 厌恶类
        val dislikePatterns = listOf(
            Regex("""我[讨厌惡怕恨不喜欢]([^，。,.!?！？]{1,30})"""),
            Regex("""我最[讨厌怕恨]([^，。,.!?！？]{1,30})""")
        )
        dislikePatterns.forEach { pattern ->
            pattern.find(text)?.groupValues?.getOrNull(1)?.let { thing ->
                val trimmed = thing.trim()
                if (trimmed.length in 2..30) {
                    results.add("用户讨厌：$trimmed")
                }
            }
        }

        // 生日/日期
        val birthdayPatterns = listOf(
            Regex("""我的生日是?([^，。,.!?！？\s]{1,20})"""),
            Regex("""我出生[于在]([^，。,.!?！？\s]{1,20})""")
        )
        birthdayPatterns.forEach { pattern ->
            pattern.find(text)?.groupValues?.getOrNull(1)?.let { date ->
                results.add("用户生日：$date")
            }
        }

        // 地点/位置
        val locationPatterns = listOf(
            Regex("""我住在([^，。,.!?！？\s]{1,20})"""),
            Regex("""我来自([^，。,.!?！？\s]{1,20})"""),
            Regex("""我的家乡在([^，。,.!?！？\s]{1,20})""")
        )
        locationPatterns.forEach { pattern ->
            pattern.find(text)?.groupValues?.getOrNull(1)?.let { loc ->
                results.add("用户所在地：$loc")
            }
        }

        // 职业/工作
        val jobPatterns = listOf(
            Regex("""我是[一名位个]([^，。,.!?！？\s]{1,15})"""),
            Regex("""我在[^，。,.!?！？]*?工作"""),
            Regex("""我的工作是([^，。,.!?！？]{1,30})"""),
            Regex("""我的职业是([^，。,.!?！？]{1,20})""")
        )
        jobPatterns.forEach { pattern ->
            val match = pattern.find(text)
            match?.let {
                val value = it.groupValues.getOrNull(1) ?: it.value
                if (value.length in 2..50) {
                    results.add("用户职业/工作：$value")
                }
            }
        }

        return results.distinct()
    }

    /**
     * 计算两个字符串的相似度（基于字符重叠度的简化版）
     * 返回 0.0 ~ 1.0
     */
    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val setA = a.toSet()
        val setB = b.toSet()
        val intersection = setA.intersect(setB).size
        val union = setA.union(setB).size
        return if (union == 0) 0.0 else intersection.toDouble() / union.toDouble()
    }

    /**
     * 手动添加一条记忆
     */
    suspend fun addMemory(personaId: String, memory: String): Boolean {
        val persona = getById(personaId) ?: return false
        val memories = (persona.memory + memory).takeLast(MAX_MEMORY_COUNT)
        val memoryJson = converters.fromStringList(memories)
        personaDao.updateMemory(personaId, memoryJson, System.currentTimeMillis())
        return true
    }

    /**
     * 删除指定索引的记忆
     */
    suspend fun removeMemory(personaId: String, index: Int): Boolean {
        val persona = getById(personaId) ?: return false
        if (index < 0 || index >= persona.memory.size) return false
        val memories = persona.memory.toMutableList().apply { removeAt(index) }
        val memoryJson = converters.fromStringList(memories)
        personaDao.updateMemory(personaId, memoryJson, System.currentTimeMillis())
        return true
    }

    /**
     * 清空某人格的所有记忆
     */
    suspend fun clearMemories(personaId: String): Boolean {
        val persona = getById(personaId) ?: return false
        val memoryJson = converters.fromStringList(emptyList())
        personaDao.updateMemory(personaId, memoryJson, System.currentTimeMillis())
        return true
    }

    // ==================== 导入导出数据类 ====================

    /**
     * 单个人格导出数据结构
     */
    private data class PersonaExportData(
        val version: Int,
        val exportTime: Long,
        val persona: Persona
    )

    /**
     * 批量人格导出数据结构
     */
    private data class PersonaListExportData(
        val version: Int,
        val exportTime: Long,
        val personas: List<Persona>
    )
}
