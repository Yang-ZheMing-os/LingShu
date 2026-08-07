package com.lingshu.agent.feature.model

/**
 * 模型能力枚举
 * 定义AI模型提供者可以支持的各种能力类型
 *
 * 包含四大核心能力：
 * - CHAT: 文本对话能力
 * - VISION: 视觉理解（图片分析）能力
 * - TRANSCRIBE: 语音识别（音频转文字）能力
 * - SYNTHESIZE: 语音合成（文字转音频）能力
 */
enum class ModelCapability {
    /** 文本对话能力 - 支持多轮对话、问答、文本生成等 */
    CHAT,

    /** 视觉理解能力 - 支持图片理解、OCR、图像描述等多模态任务 */
    VISION,

    /** 语音识别能力 - 支持将音频数据转换为文本（ASR） */
    TRANSCRIBE,

    /** 语音合成能力 - 支持将文本转换为语音音频（TTS） */
    SYNTHESIZE
}
