package com.lingshu.agent.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MessageTest {

    private lateinit var userMessage: Message
    private lateinit var assistantMessage: Message
    private lateinit var systemMessage: Message
    private lateinit var toolMessage: Message

    @Before
    fun setUp() {
        // 构造四种不同角色的消息用于测试
        userMessage = Message(
            id = "msg_user_001",
            conversationId = "conv_001",
            role = MessageRole.USER,
            content = "你好，今天天气怎么样？",
            timestamp = 1704067200000L
        )

        assistantMessage = Message(
            id = "msg_assistant_001",
            conversationId = "conv_001",
            role = MessageRole.ASSISTANT,
            content = "你好！今天北京天气晴朗，温度约23°C，适合出门。",
            timestamp = 1704067210000L,
            feedback = Message.Feedback.LIKED
        )

        systemMessage = Message(
            id = "msg_sys_001",
            conversationId = "conv_001",
            role = MessageRole.SYSTEM,
            content = "你是一个友好的AI助手",
            timestamp = 1704067100000L
        )

        toolMessage = Message(
            id = "msg_tool_001",
            conversationId = "conv_001",
            role = MessageRole.TOOL,
            content = "{\"temp\":23,\"weather\":\"clear\"}",
            timestamp = 1704067205000L
        )
    }

    @Test
    fun `测试isUserMessage方法 - USER角色返回true`() {
        // 用户消息应该被识别为用户消息
        assertTrue("USER角色消息isUserMessage应返回true", userMessage.isUserMessage())
    }

    @Test
    fun `测试isUserMessage方法 - 非USER角色返回false`() {
        // 其他三种角色都不应被识别为用户消息
        assertFalse("ASSISTANT角色消息isUserMessage应返回false", assistantMessage.isUserMessage())
        assertFalse("SYSTEM角色消息isUserMessage应返回false", systemMessage.isUserMessage())
        assertFalse("TOOL角色消息isUserMessage应返回false", toolMessage.isUserMessage())
    }

    @Test
    fun `测试isAssistantMessage方法 - ASSISTANT角色返回true`() {
        // 助手消息应该被识别为助手消息
        assertTrue("ASSISTANT角色消息isAssistantMessage应返回true", assistantMessage.isAssistantMessage())
    }

    @Test
    fun `测试isAssistantMessage方法 - 非ASSISTANT角色返回false`() {
        // 其他三种角色都不应被识别为助手消息
        assertFalse("USER角色消息isAssistantMessage应返回false", userMessage.isAssistantMessage())
        assertFalse("SYSTEM角色消息isAssistantMessage应返回false", systemMessage.isAssistantMessage())
        assertFalse("TOOL角色消息isAssistantMessage应返回false", toolMessage.isAssistantMessage())
    }

    @Test
    fun `测试isSystemMessage方法 - SYSTEM角色返回true`() {
        // 系统消息应该被识别为系统消息
        assertTrue("SYSTEM角色消息isSystemMessage应返回true", systemMessage.isSystemMessage())
    }

    @Test
    fun `测试Message数据类equals和hashCode - 相同ID内容相等`() {
        // 两个ID相同的消息应该相等
        val copyUser = userMessage.copy()
        assertEquals("同ID同内容的Message应equals相等", userMessage, copyUser)
        assertEquals("同ID同内容的Message应hashCode相等", userMessage.hashCode(), copyUser.hashCode())
    }

    @Test
    fun `测试Message数据类equals - 不同ID不相等`() {
        // ID不同的消息不应该相等
        val differentId = userMessage.copy(id = "msg_user_999")
        assertNotEquals("不同ID的Message不应equals相等", userMessage, differentId)
    }

    @Test
    fun `测试Message数据类copy方法 - 修改content字段`() {
        // copy方法修改content应该生成新对象
        val modified = userMessage.copy(content = "修改后的内容")
        assertNotEquals("copy后content不同，对象不应相等", userMessage, modified)
        assertEquals("修改后的content应生效", "修改后的内容", modified.content)
        assertEquals("其他字段应保持不变", userMessage.id, modified.id)
        assertEquals("role字段应保持不变", MessageRole.USER, modified.role)
    }

    @Test
    fun `测试Message默认值 - 默认角色为USER`() {
        // 不传role时默认是USER
        val defaultRoleMsg = Message(content = "默认消息")
        assertEquals("Message默认角色应为USER", MessageRole.USER, defaultRoleMsg.role)
        assertTrue("默认角色消息isUserMessage应返回true", defaultRoleMsg.isUserMessage())
    }

    @Test
    fun `测试Message images列表默认值 - 默认为空列表`() {
        // 不传images时默认为空List
        assertTrue("默认images应为空列表", userMessage.images.isEmpty())
        assertTrue("默认images应为emptyList", assistantMessage.images.isEmpty())
    }

    @Test
    fun `测试Message带图片和音频的构造`() {
        // 测试多模态消息的构造
        val multimodalMsg = Message(
            id = "msg_multi_001",
            role = MessageRole.USER,
            content = "描述这张图片",
            images = listOf("/sdcard/Pictures/img1.jpg", "/sdcard/Pictures/img2.jpg"),
            audioUrl = "https://example.com/audio.mp3"
        )
        assertEquals("images列表长度应为2", 2, multimodalMsg.images.size)
        assertEquals("第1张图片路径应匹配", "/sdcard/Pictures/img1.jpg", multimodalMsg.images[0])
        assertEquals("audioUrl应正确设置", "https://example.com/audio.mp3", multimodalMsg.audioUrl)
    }

    @Test
    fun `测试Message Feedback枚举 - LIKED和DISLIKED存在`() {
        // 测试Feedback枚举值
        val likedMsg = assistantMessage.copy(feedback = Message.Feedback.LIKED)
        val dislikedMsg = assistantMessage.copy(feedback = Message.Feedback.DISLIKED)
        assertEquals("LIKED反馈应正确设置", Message.Feedback.LIKED, likedMsg.feedback)
        assertEquals("DISLIKED反馈应正确设置", Message.Feedback.DISLIKED, dislikedMsg.feedback)
    }
}
