package com.lingshu.agent.core.model.routing

import android.graphics.Bitmap

/**
 * 模型提供者抽象接口
 * 定义所有AI模型提供者必须实现的核心能力
 *
 * 包含四大核心能力：
 * 1. 文本对话（chat）
 * 2. 视觉理解（vision）
 * 3. 语音识别（transcribe）
 * 4. 语音合成（synthesize）
 */
interface ModelProvider {

    /**
     * 获取该Provider对应的模型类型
     */
    val modelType: ModelType

    /**
     * 获取当前配置
     */
    val config: ModelConfig

    /**
     * 更新配置
     * @param newConfig 新的模型配置
     */
    fun updateConfig(newConfig: ModelConfig)

    /**
     * 检查模型是否可用
     * 包括：配置有效性、网络连通性（云端模型）、服务运行状态（本地模型）等
     * @return 是否可用
     */
    suspend fun isAvailable(): Boolean

    /**
     * 文本对话
     * 发送消息列表给模型，获取模型的响应
     * @param messages 对话消息列表（按时间顺序排列）
     * @return 模型响应结果
     */
    suspend fun chat(messages: List<Message>): Response

    /**
     * 视觉理解
     * 将图片和提示词发送给多模态模型，获取对图片的理解描述
     * @param image 输入的图片Bitmap
     * @param prompt 提示词，用于指定需要对图片执行的操作
     * @return 视觉理解结果文本
     */
    suspend fun vision(image: Bitmap, prompt: String): String

    /**
     * 语音识别（ASR）
     * 将音频数据转换为文本
     * @param audio 音频原始字节数组
     * @return 识别出的文本
     */
    suspend fun transcribe(audio: ByteArray): String

    /**
     * 语音合成（TTS）
     * 将文本转换为语音音频
     * @param text 要合成的文本
     * @return 合成的音频字节数组
     */
    suspend fun synthesize(text: String): ByteArray

    /**
     * 释放资源
     * 在Provider不再使用时调用，用于关闭连接、清理缓存等
     */
    fun release()
}
