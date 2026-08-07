package com.lingshu.agent.feature.personality

import org.json.JSONArray
import org.json.JSONObject

/**
 * 人格演化状态数据类（模块5+6 对齐规格书）
 *
 * 核心字段对齐规格书：
 * - personaId / name / systemPrompt / traits(OCEAN五维 BigFiveTraits) / voiceId / temperature / memoryIds
 * - 默认 name="灵枢"，全部 traits=0.5~0.7
 * - 单次变化幅度限制 <0.05
 * - DataStore JSON 持久化
 */
data class PersonalityState(
    val personaId: String = "default",
    val name: String = "灵枢",
    val systemPrompt: String = "",
    val traits: OCeanTraits = OCeanTraits(),
    val voiceId: String = "",
    val temperature: Float = 0.7f,
    val memoryIds: List<String> = emptyList(),
    val openingLine: String = "",
    val toneTags: List<String> = emptyList()
) {
    companion object {
        fun fromJson(json: String): PersonalityState {
            return try {
                val obj = JSONObject(json)
                PersonalityState(
                    personaId = obj.optString("personaId", "default"),
                    name = obj.optString("name", "灵枢"),
                    systemPrompt = obj.optString("systemPrompt", ""),
                    traits = OCeanTraits(
                        openness = obj.optDouble("openness", 0.6),
                        conscientiousness = obj.optDouble("conscientiousness", 0.6),
                        extraversion = obj.optDouble("extraversion", 0.5),
                        agreeableness = obj.optDouble("agreeableness", 0.7),
                        neuroticism = obj.optDouble("neuroticism", 0.5)
                    ),
                    voiceId = obj.optString("voiceId", ""),
                    temperature = obj.optDouble("temperature", 0.7).toFloat(),
                    memoryIds = obj.optJSONArray("memoryIds")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    } ?: emptyList(),
                    openingLine = obj.optString("openingLine", ""),
                    toneTags = obj.optJSONArray("toneTags")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    } ?: emptyList()
                )
            } catch (_: Exception) {
                PersonalityState()
            }
        }
    }

    fun toJson(): String {
        val obj = JSONObject()
        obj.put("personaId", personaId)
        obj.put("name", name)
        obj.put("systemPrompt", systemPrompt)
        obj.put("openness", traits.openness)
        obj.put("conscientiousness", traits.conscientiousness)
        obj.put("extraversion", traits.extraversion)
        obj.put("agreeableness", traits.agreeableness)
        obj.put("neuroticism", traits.neuroticism)
        obj.put("voiceId", voiceId)
        obj.put("temperature", temperature.toDouble())
        obj.put("memoryIds", JSONArray(memoryIds))
        obj.put("openingLine", openingLine)
        obj.put("toneTags", JSONArray(toneTags))
        return obj.toString()
    }
}

/**
 * OCEAN 大五人格维度
 * - openness: 开放性（好奇探索 vs 保守传统）
 * - conscientiousness: 尽责性（自律有序 vs 灵活随性）
 * - extraversion: 外向性（热情社交 vs 内敛独处）
 * - agreeableness: 宜人性（友善合作 vs 独立竞争）
 * - neuroticism: 神经质（敏感感性 vs 沉稳理性）
 *
 * 默认初始值对齐规格书：0.5~0.7
 */
data class OCeanTraits(
    val openness: Double = 0.6,
    val conscientiousness: Double = 0.6,
    val extraversion: Double = 0.5,
    val agreeableness: Double = 0.7,
    val neuroticism: Double = 0.5
) {
    fun clamp(): OCeanTraits {
        return OCeanTraits(
            openness = openness.coerceIn(0.0, 1.0),
            conscientiousness = conscientiousness.coerceIn(0.0, 1.0),
            extraversion = extraversion.coerceIn(0.0, 1.0),
            agreeableness = agreeableness.coerceIn(0.0, 1.0),
            neuroticism = neuroticism.coerceIn(0.0, 1.0)
        )
    }
}
