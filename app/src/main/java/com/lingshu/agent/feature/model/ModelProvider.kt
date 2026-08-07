package com.lingshu.agent.feature.model

import android.graphics.Bitmap
import kotlinx.coroutines.flow.Flow

/**
 * 模型提供者抽象接口
 *
 * 定义所有AI模型提供者必须实现的核心能力契约。
 * 包含四大核心能力：文本对话、视觉理解、语音识别、语音合成。
 *
 * 每个提供者（Provider）对应一个具体的AI模型服务，例如：
 * - DeepSeekProvider: DeepSeek云端大模型
 * - OllamaProvider: 本地Ollama模型服务
 * - GPT4VisionProvider: GPT-4V视觉模型
 * - VoskTranscribeProvider: Vosk离线语音识别
 * - SystemTTSProvider: 系统TTS语音合成
 *
 * ModelRouter会根据能力自动选择合适的Provider执行任务。
 */
interface ModelProvider {

    /**
     * 模型提供者唯一ID
     * 用于在ModelRouter中标识和切换不同的提供者
     * 例如："deepseek", "ollama", "gpt4-vision", "vosk", "system-tts"
     */
    val providerId: String

    /**
     * 模型提供者显示名称
     * 用于在UI设置中展示给用户
     * 例如："DeepSeek 深度求索", "Ollama 本地模型"
     */
    val providerName: String

    /**
     * 该提供者支持的能力集合
     * 用于路由时判断能否执行特定任务
     * 例如：DeepSeek支持CHAT，GPT4Vision支持CHAT+VISION
     */
    val capabilities: Set<ModelCapability>

    /**
     * 判断该提供者是否支持指定能力
     *
     * @param capability 要检查的能力
     * @return 是否支持该能力
     */
    fun supports(capability: ModelCapability): Boolean = capability in capabilities

    /**
     * 检查模型提供者当前是否可用
     *
     * 可用性检查包括：
     * - 云端模型：API Key是否配置、网络是否连通、服务是否响应
     * - 本地模型：服务是否运行、模型是否加载、资源是否就绪
     *
     * @return 是否可用
     */
    suspend fun isAvailable(): Boolean

    /**
     * 文本对话（非流式）
     *
     * 发送消息列表给模型，获取完整的模型响应。
     * 适用于需要一次性拿到完整结果的场景。
     *
     * @param messages 对话消息列表（按时间顺序排列，包含历史上下文）
     * @return 模型响应结果（包含状态、内容、Token使用等信息）
     */
    suspend fun chat(messages: List<ModelMessage>): ModelResponse

    /**
     * 文本对话（流式响应）
     *
     * 以Flow形式流式返回模型生成的内容片段，
     * 适用于打字机效果展示，提升用户体验。
     *
     * 默认实现为非流式结果包装为Flow，子类可重写实现真正的流式。
     *
     * @param messages 对话消息列表
     * @return Flow<String> 流式输出的文本片段
     */
    suspend fun chatStream(messages: List<ModelMessage>): Flow<String> {
        return kotlinx.coroutines.flow.flow {
            val response = chat(messages)
            if (response.isSuccess) {
                emit(response.content)
            } else {
                throw Exception(response.errorMessage ?: "模型调用失败")
            }
        }
    }

    /**
     * 视觉理解（图片分析）
     *
     * 将图片和提示词发送给多模态模型，获取对图片的理解描述。
     * 支持：图片描述、OCR文字识别、对象检测、视觉问答等。
     *
     * @param image 输入的图片Bitmap（Android原生图片对象）
     * @param prompt 提示词，用于指定需要对图片执行的操作
     *               例如："描述这张图片"、"提取图片中的文字"
     * @return 视觉理解结果文本
     * @throws UnsupportedOperationException 如果该提供者不支持视觉能力
     * @throws Exception 如果调用过程中发生错误（网络、解析等）
     */
    suspend fun vision(image: Bitmap, prompt: String): String

    /**
     * 语音识别（ASR - 音频转文字）
     *
     * 将音频字节数据转换为文字转录文本。
     * 音频格式通常为16kHz/16bit/单声道PCM，具体取决于实现。
     *
     * @param audio 音频原始字节数组
     * @return 识别出的文字内容
     * @throws UnsupportedOperationException 如果该提供者不支持语音识别能力
     * @throws Exception 如果识别过程中发生错误
     */
    suspend fun transcribe(audio: ByteArray): String

    /**
     * 语音合成（TTS - 文字转音频）
     *
     * 将文本内容合成为语音音频字节数组。
     * 音频格式通常为WAV/MP3等，具体取决于实现。
     *
     * @param text 要合成的文本内容
     * @return 合成的音频字节数组
     * @throws UnsupportedOperationException 如果该提供者不支持语音合成能力
     * @throws Exception 如果合成过程中发生错误
     */
    suspend fun synthesize(text: String): ByteArray

    /**
     * 释放资源
     *
     * 在Provider不再使用时调用，用于：
     * - 关闭网络连接
     * - 释放本地模型资源
     * - 清理临时缓存
     * - 停止后台任务
     */
    fun release()
}
