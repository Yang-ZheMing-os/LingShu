package com.lingshu.feature.persona.data

import com.lingshu.core.common.log.LingShuLog

enum class Sentiment {
    POSITIVE,
    NEGATIVE,
    NEUTRAL
}

enum class Tone {
    CASUAL,
    FORMAL,
    NEUTRAL
}

data class SentimentAnalysisResult(
    val sentiment: Sentiment,
    val tone: Tone,
    val expressesLiking: Boolean,
    val expressesDisliking: Boolean
)

class SentimentAnalyzer {

    fun analyze(userInput: String, aiResponse: String): SentimentAnalysisResult {
        val combinedText = "$userInput $aiResponse"
        val sentiment = analyzeSentiment(combinedText)
        val tone = analyzeTone(combinedText)
        val expressesLiking = checkLiking(userInput)
        val expressesDisliking = checkDisliking(userInput)

        LingShuLog.d(TAG, "情感分析结果: sentiment=$sentiment, tone=$tone, liking=$expressesLiking, disliking=$expressesDisliking")

        return SentimentAnalysisResult(
            sentiment = sentiment,
            tone = tone,
            expressesLiking = expressesLiking,
            expressesDisliking = expressesDisliking
        )
    }

    private fun analyzeSentiment(text: String): Sentiment {
        val lowerText = text.lowercase()

        val positiveWords = listOf(
            "喜欢", "爱", "开心", "高兴", "快乐", "棒", "好", "优秀", "赞", "感谢",
            "谢谢", "太棒了", "真好", "满意", "喜欢", "可爱", "温暖", "友好",
            "happy", "love", "great", "good", "nice", "awesome", "thank", "like",
            "wonderful", "excellent", "amazing", "perfect", "glad", "joy"
        )

        val negativeWords = listOf(
            "讨厌", "不喜欢", "生气", "难过", "伤心", "糟糕", "差", "坏", "失望",
            "烦人", "可恶", "讨厌", "害怕", "担心", "焦虑", "压力", "悲伤",
            "hate", "dislike", "angry", "sad", "bad", "terrible", "awful", "worried",
            "stressed", "upset", "frustrated", "disappointed", "afraid", "scared"
        )

        var positiveCount = 0
        var negativeCount = 0

        positiveWords.forEach { word ->
            if (lowerText.contains(word)) {
                positiveCount++
            }
        }

        negativeWords.forEach { word ->
            if (lowerText.contains(word)) {
                negativeCount++
            }
        }

        return when {
            positiveCount > negativeCount -> Sentiment.POSITIVE
            negativeCount > positiveCount -> Sentiment.NEGATIVE
            else -> Sentiment.NEUTRAL
        }
    }

    private fun analyzeTone(text: String): Tone {
        val lowerText = text.lowercase()

        val casualWords = listOf(
            "哈哈", "嘿嘿", "嘻嘻", "啦", "呀", "呢", "吧", "哦", "嗯", "哈哈哈哈",
            "233", "hhh", "lol", "lmao", "haha", "笑死", "逗", "好玩", "有趣",
            "随便", "都行", "没事儿", "没关系", "不要紧"
        )

        val formalWords = listOf(
            "您好", "请", "谢谢", "感谢", "抱歉", "对不起", "劳驾", "麻烦",
            "请问", "能否", "是否可以", "敬请", "谨此", "此致",
            "dear", "please", "thank you", "sincerely", "regards",
            "would you", "could you", "i would like to"
        )

        var casualCount = 0
        var formalCount = 0

        casualWords.forEach { word ->
            if (lowerText.contains(word)) {
                casualCount++
            }
        }

        formalWords.forEach { word ->
            if (lowerText.contains(word)) {
                formalCount++
            }
        }

        return when {
            casualCount > formalCount -> Tone.CASUAL
            formalCount > casualCount -> Tone.FORMAL
            else -> Tone.NEUTRAL
        }
    }

    private fun checkLiking(text: String): Boolean {
        val lowerText = text.lowercase()
        val likingPatterns = listOf(
            "我喜欢你", "喜欢你", "我好喜欢你", "真的喜欢你",
            "你真好", "你太棒了", "你好棒", "你真厉害",
            "i like you", "i love you", "you're great", "you are great",
            "you're awesome", "you are awesome", "i really like you"
        )

        return likingPatterns.any { pattern ->
            lowerText.contains(pattern)
        }
    }

    private fun checkDisliking(text: String): Boolean {
        val lowerText = text.lowercase()
        val dislikingPatterns = listOf(
            "我不喜欢你", "不喜欢你", "讨厌你", "你真讨厌",
            "你太差了", "你真糟糕", "你不好",
            "i don't like you", "i dislike you", "i hate you",
            "you're bad", "you are bad", "you're terrible"
        )

        return dislikingPatterns.any { pattern ->
            lowerText.contains(pattern)
        }
    }

    companion object {
        private const val TAG = "SentimentAnalyzer"
    }
}
