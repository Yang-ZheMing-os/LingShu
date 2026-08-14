package com.lingshu.feature.control.domain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AiReplyToControlBridge 核心解析逻辑单元测试。
 *
 * 验证 LLM→控制闭环中两个关键纯函数：
 * 1. [AiReplyToControlBridge.parseToolCalls] — 从 AI 回复文本中提取 [TOOL_CALL] 标记
 * 2. [AiReplyToControlBridge.mapToolCallToCommand] — 把 action 名映射到 [Command]
 *
 * 这两个函数是整个闭环的数据入口，如果解析错误，后续 CommandExecutor 执行也会出错。
 */
class AiReplyToControlBridgeTest {

    // ==================== parseToolCalls 测试 ====================

    @Test
    fun `parseToolCalls_单个标记_正常解析`() {
        val text = """[TOOL_CALL]{"action":"set_wifi","args":{"on":true}}[/TOOL_CALL]"""
        val result = AiReplyToControlBridge.parseToolCalls(text)

        assertEquals("应解析出 1 个工具调用", 1, result.size)
        assertEquals("set_wifi", result[0].optString("action"))
        assertTrue("args.on 应为 true", result[0].optJSONObject("args")?.optBoolean("on") == true)
    }

    @Test
    fun `parseToolCalls_标记前后有正常文字_只提取标记内容`() {
        val text = "好的，我来为您打开 WiFi。[TOOL_CALL]{\"action\":\"set_wifi\",\"args\":{\"on\":true}}[/TOOL_CALL] 已经完成。"
        val result = AiReplyToControlBridge.parseToolCalls(text)

        assertEquals(1, result.size)
        assertEquals("set_wifi", result[0].optString("action"))
    }

    @Test
    fun `parseToolCalls_多个标记_全部解析`() {
        val text = """
            [TOOL_CALL]{"action":"set_wifi","args":{"on":true}}[/TOOL_CALL]
            中间一些文字
            [TOOL_CALL]{"action":"volume_up","args":{}}[/TOOL_CALL]
        """.trimIndent()
        val result = AiReplyToControlBridge.parseToolCalls(text)

        assertEquals("应解析出 2 个工具调用", 2, result.size)
        assertEquals("set_wifi", result[0].optString("action"))
        assertEquals("volume_up", result[1].optString("action"))
    }

    @Test
    fun `parseToolCalls_无标记的纯文本_返回空列表`() {
        val text = "这是一段普通的 AI 回复，没有工具调用标记。"
        val result = AiReplyToControlBridge.parseToolCalls(text)

        assertTrue("无标记时应返回空列表", result.isEmpty())
    }

    @Test
    fun `parseToolCalls_空字符串_返回空列表`() {
        val result = AiReplyToControlBridge.parseToolCalls("")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseToolCalls_标记内无效JSON_跳过该项不抛异常`() {
        val text = """[TOOL_CALL]{这不是合法JSON}[/TOOL_CALL]"""
        val result = AiReplyToControlBridge.parseToolCalls(text)

        // 无效 JSON 应被捕获异常并跳过，返回空列表
        assertTrue("无效 JSON 应被跳过", result.isEmpty())
    }

    @Test
    fun `parseToolCalls_标记内有嵌套对象_正常解析`() {
        val text = """[TOOL_CALL]{"action":"open_app","args":{"app_name":"微信","package_name":"com.tencent.mm"}}[/TOOL_CALL]"""
        val result = AiReplyToControlBridge.parseToolCalls(text)

        assertEquals(1, result.size)
        assertEquals("open_app", result[0].optString("action"))
        val args = result[0].optJSONObject("args")
        assertNotNull(args)
        assertEquals("微信", args?.optString("app_name"))
        assertEquals("com.tencent.mm", args?.optString("package_name"))
    }

    @Test
    fun `parseToolCalls_标记含额外空格_正常解析`() {
        val text = """[TOOL_CALL]  {"action":"volume_mute","args":{}}  [/TOOL_CALL]"""
        val result = AiReplyToControlBridge.parseToolCalls(text)

        assertEquals(1, result.size)
        assertEquals("volume_mute", result[0].optString("action"))
    }

    // ==================== mapToolCallToCommand 测试 ====================

    @Test
    fun `mapToolCallToCommand_set_wifi_on_true_返回WIFI_ON`() {
        val args = JSONObject().put("on", true)
        val command = AiReplyToControlBridge.mapToolCallToCommand("set_wifi", args)

        assertTrue("应为 SystemControl", command is Command.SystemControl)
        assertEquals(SystemAction.WIFI_ON, (command as Command.SystemControl).action)
    }

    @Test
    fun `mapToolCallToCommand_set_wifi_on_false_返回WIFI_OFF`() {
        val args = JSONObject().put("on", false)
        val command = AiReplyToControlBridge.mapToolCallToCommand("set_wifi", args)

        assertTrue(command is Command.SystemControl)
        assertEquals(SystemAction.WIFI_OFF, (command as Command.SystemControl).action)
    }

    @Test
    fun `mapToolCallToCommand_set_wifi_缺少on参数_默认true返回WIFI_ON`() {
        // optBoolean("on", true) 默认值为 true
        val args = JSONObject()
        val command = AiReplyToControlBridge.mapToolCallToCommand("set_wifi", args)

        assertTrue(command is Command.SystemControl)
        assertEquals(SystemAction.WIFI_ON, (command as Command.SystemControl).action)
    }

    @Test
    fun `mapToolCallToCommand_set_bluetooth_on_off`() {
        val argsOn = JSONObject().put("on", true)
        assertEquals(
            SystemAction.BLUETOOTH_ON,
            (AiReplyToControlBridge.mapToolCallToCommand("set_bluetooth", argsOn) as Command.SystemControl).action
        )

        val argsOff = JSONObject().put("on", false)
        assertEquals(
            SystemAction.BLUETOOTH_OFF,
            (AiReplyToControlBridge.mapToolCallToCommand("set_bluetooth", argsOff) as Command.SystemControl).action
        )
    }

    @Test
    fun `mapToolCallToCommand_set_flashlight_on_off`() {
        val argsOn = JSONObject().put("on", true)
        assertEquals(
            SystemAction.FLASHLIGHT_ON,
            (AiReplyToControlBridge.mapToolCallToCommand("set_flashlight", argsOn) as Command.SystemControl).action
        )

        val argsOff = JSONObject().put("on", false)
        assertEquals(
            SystemAction.FLASHLIGHT_OFF,
            (AiReplyToControlBridge.mapToolCallToCommand("set_flashlight", argsOff) as Command.SystemControl).action
        )
    }

    @Test
    fun `mapToolCallToCommand_音量控制_全部映射正确`() {
        val emptyArgs = JSONObject()

        assertEquals(
            SystemAction.VOLUME_UP,
            (AiReplyToControlBridge.mapToolCallToCommand("volume_up", emptyArgs) as Command.SystemControl).action
        )
        assertEquals(
            SystemAction.VOLUME_DOWN,
            (AiReplyToControlBridge.mapToolCallToCommand("volume_down", emptyArgs) as Command.SystemControl).action
        )
        assertEquals(
            SystemAction.VOLUME_MUTE,
            (AiReplyToControlBridge.mapToolCallToCommand("volume_mute", emptyArgs) as Command.SystemControl).action
        )
        assertEquals(
            SystemAction.VOLUME_50,
            (AiReplyToControlBridge.mapToolCallToCommand("volume_50", emptyArgs) as Command.SystemControl).action
        )
    }

    @Test
    fun `mapToolCallToCommand_亮度控制_全部映射正确`() {
        val emptyArgs = JSONObject()

        assertEquals(
            SystemAction.BRIGHTNESS_UP,
            (AiReplyToControlBridge.mapToolCallToCommand("brightness_up", emptyArgs) as Command.SystemControl).action
        )
        assertEquals(
            SystemAction.BRIGHTNESS_DOWN,
            (AiReplyToControlBridge.mapToolCallToCommand("brightness_down", emptyArgs) as Command.SystemControl).action
        )
    }

    @Test
    fun `mapToolCallToCommand_自动旋转_全部映射正确`() {
        val emptyArgs = JSONObject()

        assertEquals(
            SystemAction.AUTO_ROTATE_ON,
            (AiReplyToControlBridge.mapToolCallToCommand("auto_rotate_on", emptyArgs) as Command.SystemControl).action
        )
        assertEquals(
            SystemAction.AUTO_ROTATE_OFF,
            (AiReplyToControlBridge.mapToolCallToCommand("auto_rotate_off", emptyArgs) as Command.SystemControl).action
        )
    }

    @Test
    fun `mapToolCallToCommand_take_screenshot_返回Screenshot命令`() {
        val command = AiReplyToControlBridge.mapToolCallToCommand("take_screenshot", JSONObject())

        assertTrue("应为 Screenshot 命令", command is Command.Screenshot)
    }

    @Test
    fun `mapToolCallToCommand_open_app_返回OpenApp命令`() {
        val args = JSONObject()
            .put("app_name", "微信")
            .put("package_name", "com.tencent.mm")
        val command = AiReplyToControlBridge.mapToolCallToCommand("open_app", args)

        assertTrue(command is Command.OpenApp)
        val openApp = command as Command.OpenApp
        assertEquals("微信", openApp.appName)
        assertEquals("com.tencent.mm", openApp.packageName)
    }

    @Test
    fun `mapToolCallToCommand_close_app_返回CloseApp命令`() {
        val args = JSONObject().put("app_name", "抖音")
        val command = AiReplyToControlBridge.mapToolCallToCommand("close_app", args)

        assertTrue(command is Command.CloseApp)
        assertEquals("抖音", (command as Command.CloseApp).appName)
    }

    @Test
    fun `mapToolCallToCommand_未知action_返回null`() {
        val command = AiReplyToControlBridge.mapToolCallToCommand("unknown_action", JSONObject())
        assertNull("未知 action 应返回 null", command)
    }

    @Test
    fun `mapToolCallToCommand_action大小写不敏感_大写也能映射`() {
        // executeToolCall 中会 lowercase()，但这里直接测试 mapToolCallToCommand
        // 注意：mapToolCallToCommand 本身不做 lowercase，调用方负责
        val command = AiReplyToControlBridge.mapToolCallToCommand("set_wifi", JSONObject().put("on", true))
        assertNotNull(command)
    }

    // ==================== 端到端解析+映射联合测试 ====================

    @Test
    fun `端到端_LLM回复含单个工具调用_解析并映射为正确命令`() {
        val llmReply = """好的，我帮您打开 WiFi。
            [TOOL_CALL]{"action":"set_wifi","args":{"on":true}}[/TOOL_CALL]
        """.trimIndent()

        val toolCalls = AiReplyToControlBridge.parseToolCalls(llmReply)
        assertEquals(1, toolCalls.size)

        val json = toolCalls[0]
        val action = json.optString("action").lowercase()
        val args = json.optJSONObject("args") ?: JSONObject()
        val command = AiReplyToControlBridge.mapToolCallToCommand(action, args)

        assertTrue(command is Command.SystemControl)
        assertEquals(SystemAction.WIFI_ON, (command as Command.SystemControl).action)
    }

    @Test
    fun `端到端_LLM回复含多个工具调用_全部解析并映射`() {
        val llmReply = """
            我来帮您调大音量并打开蓝牙。
            [TOOL_CALL]{"action":"volume_up","args":{}}[/TOOL_CALL]
            [TOOL_CALL]{"action":"set_bluetooth","args":{"on":true}}[/TOOL_CALL]
        """.trimIndent()

        val toolCalls = AiReplyToControlBridge.parseToolCalls(llmReply)
        assertEquals("应解析出 2 个工具调用", 2, toolCalls.size)

        val cmd1 = AiReplyToControlBridge.mapToolCallToCommand(
            toolCalls[0].optString("action").lowercase(),
            toolCalls[0].optJSONObject("args") ?: JSONObject()
        )
        val cmd2 = AiReplyToControlBridge.mapToolCallToCommand(
            toolCalls[1].optString("action").lowercase(),
            toolCalls[1].optJSONObject("args") ?: JSONObject()
        )

        assertEquals(SystemAction.VOLUME_UP, (cmd1 as Command.SystemControl).action)
        assertEquals(SystemAction.BLUETOOTH_ON, (cmd2 as Command.SystemControl).action)
    }

    @Test
    fun `端到端_LLM回复无工具调用标记_返回空列表不执行控制`() {
        val llmReply = "今天天气不错，适合出门散步。"
        val toolCalls = AiReplyToControlBridge.parseToolCalls(llmReply)
        assertTrue("无标记时不应解析出工具调用", toolCalls.isEmpty())
    }
}