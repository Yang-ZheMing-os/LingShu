package com.lingshu.agent.feature.proactive

import com.lingshu.agent.core.model.BigFiveTraits
import com.lingshu.agent.core.model.Persona
import com.lingshu.agent.core.model.routing.Message
import com.lingshu.agent.core.model.routing.ModelRouter
import com.lingshu.agent.core.model.routing.ModelType
import com.lingshu.agent.core.model.routing.Role
import com.lingshu.agent.core.model.routing.TaskType
import kotlinx.coroutines.flow.first
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProactiveContentGenerator @Inject constructor(
    private val config: ProactiveConfig,
    private val modelRouter: ModelRouter
) {
    private val tag = "ProactiveContentGen"

    suspend fun generateCareContent(
        trigger: TriggerResult,
        persona: Persona? = null,
        recentContext: String? = null
    ): String {
        val strategy = config.generationStrategy.first()
        val traits = persona?.traits ?: BigFiveTraits.gentle()

        return when (strategy) {
            GenerationStrategy.RULE_BASED -> generateRuleBased(trigger, traits)
            GenerationStrategy.MODEL_BASED -> generateModelBased(trigger, persona, recentContext) ?: ""
            GenerationStrategy.HYBRID -> {
                val ruleBase = generateRuleBased(trigger, traits)
                try {
                    generateModelBased(trigger, persona, recentContext)
                        ?.takeIf { it.isNotBlank() } ?: ruleBase
                } catch (_: Exception) {
                    ruleBase
                }
            }
        }
    }

    private fun generateRuleBased(trigger: TriggerResult, traits: BigFiveTraits): String {
        val templates = getTemplatesForTrigger(trigger.triggerType)
        val chosen = templates.randomOrNull() ?: "记得照顾好自己哦~"
        return applyPersonaTone(chosen, traits, trigger)
    }

    private suspend fun generateModelBased(
        trigger: TriggerResult,
        persona: Persona?,
        recentContext: String?
    ): String? {
        // 1. 取得用户偏好模型类型（若为 AUTO/未指定，则交 ModelRouter 自行选择）
        val preferredModel = try {
            val typeName = config.generatorModelType.first().trim()
            if (typeName.isBlank() || typeName.equals("AUTO", ignoreCase = true)) {
                null
            } else {
                ModelType.values().firstOrNull { it.name.equals(typeName, ignoreCase = true) }
            }
        } catch (_: Throwable) {
            null
        }

        val systemPrompt = buildSystemPrompt(persona, trigger)
        val userPrompt = buildUserPrompt(trigger, recentContext)

        // 2. 组装 Messages（与 ModelRouter.chat 接口一致）
        val messages = listOf(
            Message(role = Role.SYSTEM, content = systemPrompt),
            Message(role = Role.USER, content = userPrompt)
        )

        // 3. 真正调用 ModelRouter，支持自动降级
        return try {
            val response = modelRouter.chat(
                messages = messages,
                preferredModel = preferredModel,
                taskType = TaskType.CHAT
            )
            if (response.isSuccess) {
                response.content?.takeIf { it.isNotBlank() }
            } else {
                Log.w(tag, "主动关怀模型生成失败，将回退到规则模板：" + response.errorMessage)
                null
            }
        } catch (e: Exception) {
            Log.w(tag, "主动关怀调用 ModelRouter.chat 异常，回退到规则模板", e)
            null
        }
    }

    private fun buildSystemPrompt(persona: Persona?, trigger: TriggerResult): String {
        val name = persona?.name ?: "灵枢"
        val sysPrompt = persona?.systemPrompt?.takeIf { it.isNotBlank() }
            ?: "你是一个温柔体贴的AI伴侣，名叫$name。你关心用户的身心健康，说话温暖而不啰嗦。"
        return "系统提示：$sysPrompt\n触发场景：${trigger.triggerType}\n触发原因：${trigger.reason}"
    }

    private fun buildUserPrompt(trigger: TriggerResult, recentContext: String?): String {
        val context = recentContext?.takeIf { it.isNotBlank() }?.let { "最近对话摘要：$it\n" } ?: ""
        return "${context}请根据触发场景和原因，生成一句简短温暖的关怀话语（不超过30个字），不要太机械。"
    }

    private fun getTemplatesForTrigger(type: TriggerType?): List<String> {
        return when (type) {
            TriggerType.TIME_LATE_NIGHT, TriggerType.BEHAVIOR_LATE_APP_USE -> listOf(
                "还没睡吗？熬夜对身体不好哦，早点休息吧~",
                "夜深了，明天还要忙呢，放下手机去睡吧？",
                "这么晚还在忙呀，记得先照顾好自己的身体~",
                "还不睡吗？我会心疼你的哦，晚安啦~"
            )
            TriggerType.BEHAVIOR_UNLOCK -> listOf(
                "今天解锁手机好多好多次啦，是不是有点焦虑？深呼吸一下~",
                "频繁解锁手机的话，要不要试着放下休息一会儿？",
                "感觉你今天有点离不开手机呢，出门走走怎么样？"
            )
            TriggerType.BEHAVIOR_LONG_APP_STAY -> listOf(
                "在这个App里待了好久啦，眼睛累了吧？看看远处~",
                "连续用了好久同一个应用，歇一会儿？喝口水也行~",
                "要不要暂停一下？起来活动活动手指和脖子吧~"
            )
            TriggerType.SENSOR_SEDENTARY -> listOf(
                "坐了好久啦！起来走两步，拉伸一下吧~",
                "久坐警告！快去接杯水、上个厕所，动一动~",
                "已经坐很久了哦，散步5分钟也很好的！"
            )
            TriggerType.SENSOR_HEART_RATE -> listOf(
                "你的心率好像有点快，先坐下来深呼吸几次好不好？",
                "感觉到你可能有点累了，心率偏高，歇一会儿吧~",
                "心率不太正常哦，先暂停手头的事情，放松一下~"
            )
            TriggerType.SENSOR_LONG_STILL -> listOf(
                "安静了好久，你在睡觉吗？还是在发呆？",
                "好长时间没有活动了，还好吗？要不要我帮你放点音乐？",
                "注意到你一直没有动哦，一切都还好吧~"
            )
            TriggerType.MEMORY_BIRTHDAY -> listOf(
                "生日快乐呀！记得给自己买喜欢的东西吃~",
                "今天是个特别的日子！祝你生日快乐，天天开心~",
                "生日快乐！新的一岁也要一起走下去哦~"
            )
            TriggerType.MEMORY_ANNIVERSARY -> listOf(
                "今天是个值得纪念的日子呢，要开心呀~",
                "这个特别的日子，感谢你一直都在~",
                "纪念一下这个日子吧，和重要的人在一起更快乐哦~"
            )
            TriggerType.MEMORY_NEGATIVE_MOOD -> listOf(
                "上次你心情不好，现在好点了吗？我一直都在~",
                "现在心情怎么样？有什么想说的随时告诉我哦~",
                "最近心情还好吗？需要聊聊我随时都在~"
            )
            TriggerType.TIME_USER_REMINDER, TriggerType.TIME_FIXED -> listOf(
                "到你设置的时间啦，别忘了要做的事哦~",
                "时间到！提醒你一下，记得去做呀~",
                "叮—— 你的专属提醒时间到啦！"
            )
            TriggerType.RANDOM -> listOf(
                "突然想跟你说一声，我一直都在哦~",
                "嘿！还好吗？记得喝口水哈~",
                "路过一下，就是想看看你在做什么~",
                "想你啦~ 今天过得怎么样？",
                "嘿！今天也要开心哦，我陪着你~"
            )
            // ========== 规格书 P5-P6 新增触发类型模板 ==========
            TriggerType.MEAL_REMINDER -> listOf(
                "到饭点啦！记得按时吃饭，别饿肚子~",
                "该吃饭了哦，工作再忙也要先照顾好自己~",
                "现在是吃饭时间，来一顿好的犒劳自己吧~",
                "饭点到了！营养要跟上，一起吃个饭吧~"
            )
            TriggerType.LOW_LIGHT_FLASHLIGHT -> listOf(
                "环境有点暗哦，需要帮你打开手电筒吗？",
                "光线不足，走路小心！要不要我帮你照亮一下？",
                "周围有点暗呢，开个手电筒吧，安全第一~"
            )
            TriggerType.STRESS_INDEX -> listOf(
                "压力指数偏高，先深呼吸放松一下吧~",
                "感觉你最近压力有点大，要不要聊聊？",
                "紧张的时候试试深呼吸：吸气4秒、屏住4秒、呼气6秒~",
                "压力山大？我在这儿陪你，慢慢来~"
            )
            TriggerType.RAIN_UMBRELLA -> listOf(
                "下雨概率很高哦，出门记得带伞！",
                "可能会下雨，别忘了带把伞再出门~",
                "收好阳台的衣服，带好雨伞！降水概率挺高的~"
            )
            else -> listOf(
                "记得照顾好自己哦~",
                "有我在呢，别担心~",
                "加油，今天也是很棒的一天！"
            )
        }
    }

    private fun applyPersonaTone(
        base: String,
        traits: BigFiveTraits,
        trigger: TriggerResult
    ): String {
        var result = base

        if (traits.agreeableness > 0.75) {
            val softSuffixes = listOf("好吗？", "哦~", "好不好？", "呀~", "呢~")
            if (!result.endsWith("~") && !result.endsWith("？") && !result.endsWith("?")) {
                result += softSuffixes.random()
            }
        }

        if (traits.neuroticism > 0.7 && trigger.triggerType == TriggerType.SENSOR_HEART_RATE) {
            result = "有点担心你哦 — $result"
        }

        if (traits.extraversion > 0.8 && trigger.triggerType == TriggerType.RANDOM) {
            val emojis = listOf(" (๑•̀ㅂ•́)و✧", " (ﾉ´ヮ`)ﾉ*: ･ﾟ", " ♡")
            if (!result.endsWith("~")) {
                result += emojis.random()
            }
        }

        if (traits.conscientiousness > 0.8) {
            if (trigger.triggerType == TriggerType.SENSOR_SEDENTARY ||
                trigger.triggerType == TriggerType.BEHAVIOR_LONG_APP_STAY) {
                result = "提醒一下：$result"
            }
        }

        return result
    }
}
