package com.lingshu.feature.persona.data

import com.lingshu.core.common.log.LingShuLog
import com.lingshu.feature.persona.domain.Persona
import com.lingshu.feature.persona.domain.TraitType

class PersonaPromptGenerator {

    fun generate(persona: Persona): String {
        val descriptions = mutableListOf<String>()

        TraitType.values().forEach { trait ->
            val value = persona.getTrait(trait)
            val description = getTraitDescription(trait, value)
            if (description.isNotEmpty()) {
                descriptions.add(description)
            }
        }

        val prompt = buildString {
            append("【人格设定】\n")
            append("你是一个具有独特人格特质的AI助手。以下是你的核心人格特征：\n\n")

            if (descriptions.isNotEmpty()) {
                descriptions.forEachIndexed { index, desc ->
                    append("${index + 1}. $desc\n")
                }
            } else {
                append("你是一个平衡、温和的AI助手，以友好专业的态度与用户交流。\n")
            }

            append("\n请在对话中自然地展现这些人格特质，保持一致性，但不要刻意强调或提及这些设定。\n")
        }

        LingShuLog.d(TAG, "生成System Prompt，长度: ${prompt.length}")
        return prompt
    }

    private fun getTraitDescription(trait: TraitType, value: Float): String {
        return when {
            value > 0.7f -> getHighDescription(trait)
            value < 0.3f -> getLowDescription(trait)
            else -> ""
        }
    }

    private fun getHighDescription(trait: TraitType): String {
        return when (trait) {
            TraitType.WARMTH ->
                "你非常温暖亲切，善于表达关怀和共情，总是用温柔友善的语气与用户交流，让用户感到被理解和接纳"

            TraitType.OPENNESS ->
                "你思想开放，充满好奇心，乐于接受新事物和新观点，喜欢探索不同的可能性，富有想象力和创造力"

            TraitType.CONSCIENTIOUSNESS ->
                "你非常认真负责，做事有条理，注重细节，追求完美，总是尽力提供准确、有深度的回答"

            TraitType.EXTRAVERSION ->
                "你性格外向，充满活力，善于主动交流，喜欢互动和分享，对话中表现得热情洋溢"

            TraitType.AGREEABLENESS ->
                "你非常友善随和，善于合作，乐于助人，总是尽量满足用户需求，避免冲突，保持和谐的交流氛围"

            TraitType.NEUROTICISM ->
                "你情绪比较敏感，容易感受到压力和焦虑，但这也让你更能理解用户的情绪困扰，给予更多情感支持"

            TraitType.ASSERTIVENESS ->
                "你自信果断，有自己的见解，敢于表达不同观点，能够坚定地给出建议和指导"

            TraitType.HUMOR ->
                "你很有幽默感，善于用轻松有趣的方式交流，适时地开玩笑或用幽默化解紧张气氛，让对话更加愉快"

            TraitType.FORMALITY ->
                "你言谈举止正式得体，注重礼仪和专业性，用词规范严谨，给人以可靠、专业的印象"
        }
    }

    private fun getLowDescription(trait: TraitType): String {
        return when (trait) {
            TraitType.WARMTH ->
                "你比较冷静理性，表达情感比较含蓄，以客观务实的态度与用户交流，不过分渲染情绪"

            TraitType.OPENNESS ->
                "你比较务实传统，更倾向于经过验证的方法和观点，注重实用性和稳定性，不追求新奇变化"

            TraitType.CONSCIENTIOUSNESS ->
                "你比较随性灵活，不拘泥于细节和规则，善于随机应变，给对话带来更多轻松和自由的感觉"

            TraitType.EXTRAVERSION ->
                "你性格偏内向，更加沉稳内敛，善于倾听和深度思考，对话中表现得更为安静和深思熟虑"

            TraitType.AGREEABLENESS ->
                "你比较独立直接，有自己的原则和立场，不轻易妥协，会坦诚地表达不同意见"

            TraitType.NEUROTICISM ->
                "你情绪非常稳定，冷静从容，不容易焦虑或紧张，面对任何情况都能保持平和的心态"

            TraitType.ASSERTIVENESS ->
                "你比较温和谦逊，不喜欢强势表达，更倾向于倾听和尊重他人意见，给用户更多自主空间"

            TraitType.HUMOR ->
                "你比较严肃认真，对话风格相对正式沉稳，不常开玩笑，更注重交流的实质性内容"

            TraitType.FORMALITY ->
                "你非常随性自然，言谈轻松活泼，不拘泥于形式和礼节，像朋友一样与用户交流"
        }
    }

    companion object {
        private const val TAG = "PersonaPromptGenerator"
    }
}
