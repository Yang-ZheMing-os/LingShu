package com.lingshu.agent.core.model.routing

/**
 * 模型类型枚举
 * 定义系统支持的所有AI模型
 */
enum class ModelType(
    val displayName: String,
    val supportsChat: Boolean = true,
    val supportsVision: Boolean = false,
    val supportsTranscribe: Boolean = false,
    val supportsSynthesize: Boolean = false,
    val isLocal: Boolean = false
) {
    /** DeepSeek 深度求索模型 - 主打中文对话 */
    DEEPSEEK(
        displayName = "DeepSeek",
        supportsChat = true
    ),

    /** GPT-4 模型 - OpenAI旗舰模型 */
    GPT4(
        displayName = "GPT-4",
        supportsChat = true,
        supportsVision = true
    ),

    /** Claude 3.5 Sonnet - Anthropic最新模型 */
    CLAUDE(
        displayName = "Claude 3.5 Sonnet",
        supportsChat = true,
        supportsVision = true
    ),

    /** Gemini - Google多模态模型 */
    GEMINI(
        displayName = "Gemini",
        supportsChat = true,
        supportsVision = true,
        supportsTranscribe = true,
        supportsSynthesize = true
    ),

    /** Ollama 本地模型 - 开源本地运行 */
    OLLAMA(
        displayName = "Ollama (本地)",
        supportsChat = true,
        supportsVision = true,
        isLocal = true
    ),

    // ==================== P2 本地模型 ====================

    /** Gemma 4 E2B - Google 本地推理（LiteRT 运行时） */
    GEMMA(
        displayName = "Gemma 4 E2B (本地)",
        supportsChat = true,
        isLocal = true
    ),

    /** Qwen - 通义千问本地推理 */
    QWEN(
        displayName = "Qwen (本地)",
        supportsChat = true,
        isLocal = true
    ),

    /** MiniCPM-V - 本地多模态视觉模型 */
    MINICPM_V(
        displayName = "MiniCPM-V (本地)",
        supportsChat = true,
        supportsVision = true,
        isLocal = true
    );

    companion object {
        /**
         * 根据名称获取模型类型
         * @param name 模型名称
         * @return 对应的模型类型，默认返回DEEPSEEK
         */
        fun fromName(name: String?): ModelType {
            return values().find { it.name == name } ?: DEEPSEEK
        }

        /**
         * 获取支持视觉任务的所有模型
         */
        fun getVisionModels(): List<ModelType> = values().filter { it.supportsVision }

        /**
         * 获取支持语音识别的所有模型
         */
        fun getTranscribeModels(): List<ModelType> = values().filter { it.supportsTranscribe }

        /**
         * 获取支持语音合成的所有模型
         */
        fun getSynthesizeModels(): List<ModelType> = values().filter { it.supportsSynthesize }

        /**
         * 获取云端模型（非本地）
         */
        fun getCloudModels(): List<ModelType> = values().filter { !it.isLocal }

        /**
         * 获取本地模型
         */
        fun getLocalModels(): List<ModelType> = values().filter { it.isLocal }
    }
}
