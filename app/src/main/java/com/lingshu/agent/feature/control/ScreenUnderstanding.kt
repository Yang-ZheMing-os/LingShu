package com.lingshu.agent.feature.control

import android.graphics.Bitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 屏幕理解模块（VLM - Vision Language Model）
 *
 * 功能：
 * 1. 调用 ScreenCaptureManager 获取屏幕截图
 * 2. 将截图发送给视觉语言模型（VLM）进行分析
 * 3. 返回屏幕内容的文字描述
 *
 * 本模块只定义接口与流程，实际VLM模型接入由实现方提供
 */
@Singleton
class ScreenUnderstanding @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
    private val screenCaptureManager: ScreenCaptureManager
) {

    // ==================== VLM 接口定义 ====================

    /**
     * VLM（视觉语言模型）引擎接口
     * 可接入 GPT-4V、Claude 3、Gemini、Qwen-VL、GLM-4V 等
     */
    interface VlmEngine {
        /** 引擎名称 */
        val name: String

        /** 引擎是否可用（是否配置了API Key等） */
        fun isAvailable(): Boolean

        /**
         * 分析图片并返回文字描述
         *
         * @param image 要分析的图片Bitmap
         * @param prompt 提示词，告诉模型应该关注什么
         * @param systemPrompt 系统提示词（可选），定义模型角色
         * @return 模型返回的分析结果
         */
        suspend fun analyzeImage(
            image: Bitmap,
            prompt: String,
            systemPrompt: String? = null
        ): VlmResult
    }

    /**
     * VLM分析结果
     */
    data class VlmResult(
        /** 模型返回的文字描述 */
        val description: String,
        /** 完整的原始响应（如果有） */
        val rawResponse: String? = null,
        /** token使用量（可选） */
        val usage: TokenUsage? = null,
        /** 分析耗时毫秒 */
        val latencyMs: Long = 0L
    )

    /**
     * Token使用量统计
     */
    data class TokenUsage(
        /** 输入图片+prompt的token数 */
        val inputTokens: Int = 0,
        /** 输出token数 */
        val outputTokens: Int = 0,
        /** 总token数 */
        val totalTokens: Int = 0
    )

    // ==================== 预置提示词 ====================

    companion object {
        /** 默认系统提示词：描述屏幕内容 */
        val SYSTEM_PROMPT_DEFAULT = """
你是一个手机屏幕内容分析助手。请仔细分析用户提供的屏幕截图，给出准确、清晰、结构化的描述。

要求：
1. 首先说明这是什么页面（如：微信聊天页、设置主页面、抖音视频播放页）
2. 列出页面上的主要元素（标题、按钮、输入框、列表项等）
3. 如有文本内容，提取关键信息
4. 如有可操作的控件，说明其大致位置（上/下/左/右/中）
5. 描述保持简洁，重点突出
        """.trimIndent()

        /** 操作指引提示词：分析后给出操作建议 */
        val PROMPT_ACTION_GUIDE = """
请分析此屏幕截图，并回答：
1. 屏幕上显示了什么内容？
2. 如果我想完成以下目标，下一步应该点击哪里？
目标：%s

请按以下格式回答：
【页面分析】
<描述>

【操作建议】
<操作步骤>
<目标控件描述>
<大致位置>
        """.trimIndent()

        /** 控件查找提示词：从页面中找到特定控件 */
        val PROMPT_FIND_ELEMENT = """
请在屏幕截图中找到以下目标控件，并描述它的位置：
目标：%s

请说明：
1. 该控件是否存在于屏幕上？
2. 如果存在，它的文字/图标/内容描述是什么？
3. 它大致在屏幕的什么位置（用百分比或方位描述）？
        """.trimIndent()
    }

    // ==================== 引擎管理 ====================

    /** 当前VLM引擎 */
    private var vlmEngine: VlmEngine? = null

    /**
     * 设置VLM引擎
     */
    fun setVlmEngine(engine: VlmEngine?) {
        vlmEngine = engine
    }

    /**
     * VLM引擎是否可用
     */
    fun isVlmAvailable(): Boolean = vlmEngine?.isAvailable() == true

    // ==================== 屏幕分析方法 ====================

    /**
     * 截屏并调用VLM进行分析
     *
     * @param prompt 用户提示词
     * @param systemPrompt 系统提示词
     * @param useOcrFirst 是否先做OCR（将OCR文本附加到prompt中辅助VLM分析）
     * @return 分析结果
     */
    suspend fun captureAndAnalyze(
        prompt: String = "请描述屏幕上的内容",
        systemPrompt: String = SYSTEM_PROMPT_DEFAULT,
        useOcrFirst: Boolean = false
    ): Result<VlmResult> = withContext(Dispatchers.Default) {
        val engine = vlmEngine
        if (engine == null || !engine.isAvailable()) {
            return@withContext Result.failure(
                IllegalStateException("VLM引擎不可用，请先调用 setVlmEngine()")
            )
        }

        return@withContext try {
            val bitmap = screenCaptureManager.captureScreenSuspend()

            var finalPrompt = prompt
            if (useOcrFirst && screenCaptureManager.isOcrAvailable()) {
                val ocrResult = screenCaptureManager.ocrBitmap(bitmap)
                if (ocrResult.text.isNotBlank()) {
                    finalPrompt = buildString {
                        append(prompt)
                        append("\n\n【辅助信息：OCR识别到的文本】\n")
                        append(ocrResult.text)
                    }
                }
            }

            val startTime = System.currentTimeMillis()
            val result = try {
                engine.analyzeImage(bitmap, finalPrompt, systemPrompt)
            } finally {
                bitmap.recycle()
            }
            val latency = System.currentTimeMillis() - startTime

            Result.success(
                result.copy(latencyMs = latency)
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 对已有Bitmap进行VLM分析
     */
    suspend fun analyzeBitmap(
        bitmap: Bitmap,
        prompt: String = "请描述图片内容",
        systemPrompt: String? = SYSTEM_PROMPT_DEFAULT
    ): Result<VlmResult> = withContext(Dispatchers.Default) {
        val engine = vlmEngine
        if (engine == null || !engine.isAvailable()) {
            return@withContext Result.failure(
                IllegalStateException("VLM引擎不可用")
            )
        }

        return@withContext try {
            val startTime = System.currentTimeMillis()
            val result = engine.analyzeImage(bitmap, prompt, systemPrompt)
            val latency = System.currentTimeMillis() - startTime
            Result.success(result.copy(latencyMs = latency))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 分析屏幕并给出操作指引
     * @param userGoal 用户想要完成的目标描述
     */
    suspend fun analyzeAndGuide(
        userGoal: String
    ): Result<VlmResult> {
        val prompt = PROMPT_ACTION_GUIDE.format(userGoal)
        return captureAndAnalyze(
            prompt = prompt,
            systemPrompt = SYSTEM_PROMPT_DEFAULT,
            useOcrFirst = true
        )
    }

    /**
     * 查找屏幕上的特定控件
     * @param targetDescription 目标控件的描述（如：发送按钮、红色的删除图标等）
     */
    suspend fun findElementOnScreen(
        targetDescription: String
    ): Result<VlmResult> {
        val prompt = PROMPT_FIND_ELEMENT.format(targetDescription)
        return captureAndAnalyze(
            prompt = prompt,
            systemPrompt = "你是一个UI元素查找助手，请在屏幕截图中找到用户指定的控件并描述其位置。",
            useOcrFirst = true
        )
    }

    // ==================== 占位引擎 ====================

    /**
     * 占位VLM引擎（不做实际分析）
     * 接入真实VLM后请使用 setVlmEngine() 替换
     */
    class PlaceholderVlmEngine : VlmEngine {
        override val name: String = "PlaceholderVLM"

        override fun isAvailable(): Boolean = false

        override suspend fun analyzeImage(
            image: Bitmap,
            prompt: String,
            systemPrompt: String?
        ): VlmResult {
            return VlmResult(
                description = "【占位】当前未配置VLM引擎。请调用 setVlmEngine() 接入真实的视觉语言模型。",
                rawResponse = null,
                usage = null,
                latencyMs = 0L
            )
        }
    }

    init {
        // 默认使用占位引擎
        if (vlmEngine == null) {
            vlmEngine = PlaceholderVlmEngine()
        }
    }
}
