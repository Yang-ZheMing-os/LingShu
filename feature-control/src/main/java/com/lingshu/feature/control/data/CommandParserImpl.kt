package com.lingshu.feature.control.data

import com.lingshu.core.common.log.LingShuLog
import com.lingshu.feature.control.domain.ChatChannel
import com.lingshu.feature.control.domain.Command
import com.lingshu.feature.control.domain.ICommandParser
import com.lingshu.feature.control.domain.ISystemControl
import com.lingshu.feature.control.domain.ScrollDirection
import com.lingshu.feature.control.domain.SystemAction
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommandParserImpl @Inject constructor(
    private val systemControl: ISystemControl,
    private val customCommandManager: CustomCommandManager
) : ICommandParser {

    override fun topSimilarExamples(userInput: String, limit: Int): List<String> {
        val raw = userInput.trim()
        if (raw.isBlank()) return CANONICAL_EXAMPLES.take(limit)
        val lowered = raw.lowercase()
        // 简单分数：chars 共现 + 关键词命中 + 编辑距离倒数
        val scored = CANONICAL_EXAMPLES.map { ex ->
            val exL = ex.lowercase()
            var s = 0f
            // 单字覆盖
            lowered.forEach { c -> if (c in exL) s += 1 }
            // 双字切片命中
            lowered.windowed(2).forEach { if (it in exL) s += 3 }
            // 完全包含
            if (exL.contains(lowered) || lowered.contains(exL)) s += 20
            s to ex
        }.sortedByDescending { it.first }
        return scored.take(limit.coerceAtLeast(1)).map { it.second }
    }

    override fun parse(userInput: String): Command {
        val raw = userInput.trim()
        val input = raw.lowercase()
        LingShuLog.d("CommandParser", "解析指令: $userInput")

        // 优先匹配用户自定义指令别名（AES256 加密存储的日常言语习惯）
        customCommandManager.resolveAlias(raw)?.let { target ->
            LingShuLog.i("CommandParser", "命中自定义指令别名: $raw -> $target")
            return parse(target)
        }

        // 去掉前导口语修饰词，方便后续匹配
        val cleaned = stripLeadingFillers(input)

        // 预解析（需要提取参数的指令）——复合场景优先级最高，避免被简单指令截走
        val orderTakeout = parseOrderTakeout(cleaned)
        val sendChatMsg  = parseSendChatMessage(cleaned)
        val callRide     = parseCallRide(cleaned)

        val navigate     = parseNavigate(cleaned)
        val appAction    = parseAppAction(cleaned)
        val uiScroll     = parseUiScroll(cleaned)
        val uiTapText    = parseUiTapText(cleaned)
        val uiInputText  = parseUiInputText(cleaned)
        val webSearch    = parseWebSearch(cleaned)
        val setAlarm     = parseSetAlarm(cleaned)
        val makeCall     = parseMakeCall(cleaned)
        val sendSms      = parseSendSms(cleaned)

        return when {
            // ===== 截屏 =====
            isScreenshotCommand(cleaned) -> Command.Screenshot

            // ===== 睡觉/静音（口语最高优先，"我要睡了"不会被当成打开XX） =====
            isSleepCommand(cleaned) -> Command.SystemControl(SystemAction.VOLUME_MUTE)

            // ===== 拍照 =====
            isCameraCommand(cleaned) -> Command.OpenCamera

            // ===== 播放音乐 =====
            isPlayMusicCommand(cleaned) -> Command.PlayMusic

            // ==================== 三大复合场景（最高优先级） ====================
            orderTakeout != null -> orderTakeout
            sendChatMsg  != null -> sendChatMsg
            callRide     != null -> callRide

            // ===== 打电话 / 发短信（保留原 API，未匹配到复合场景再用） =====
            makeCall != null -> makeCall
            sendSms  != null -> sendSms

            // ===== 闹钟 =====
            setAlarm != null -> setAlarm

            // ===== WiFi =====
            isWifiOnCommand(cleaned) -> Command.SystemControl(SystemAction.WIFI_ON)
            isWifiOffCommand(cleaned) -> Command.SystemControl(SystemAction.WIFI_OFF)

            // ===== 蓝牙 =====
            isBluetoothOnCommand(cleaned) -> Command.SystemControl(SystemAction.BLUETOOTH_ON)
            isBluetoothOffCommand(cleaned) -> Command.SystemControl(SystemAction.BLUETOOTH_OFF)

            // ===== 手电筒 =====
            isFlashlightOnCommand(cleaned) -> Command.SystemControl(SystemAction.FLASHLIGHT_ON)
            isFlashlightOffCommand(cleaned) -> Command.SystemControl(SystemAction.FLASHLIGHT_OFF)

            // ===== 亮度 =====
            isBrightnessUpCommand(cleaned) -> Command.SystemControl(SystemAction.BRIGHTNESS_UP)
            isBrightnessDownCommand(cleaned) -> Command.SystemControl(SystemAction.BRIGHTNESS_DOWN)

            // ===== 音量 =====
            isVolume50Command(cleaned) -> Command.SystemControl(SystemAction.VOLUME_50)
            isVolumeMuteCommand(cleaned) -> Command.SystemControl(SystemAction.VOLUME_MUTE)
            isVolumeUpCommand(cleaned) -> Command.SystemControl(SystemAction.VOLUME_UP)
            isVolumeDownCommand(cleaned) -> Command.SystemControl(SystemAction.VOLUME_DOWN)

            // ===== 自动旋转 =====
            isAutoRotateOnCommand(cleaned) -> Command.SystemControl(SystemAction.AUTO_ROTATE_ON)
            isAutoRotateOffCommand(cleaned) -> Command.SystemControl(SystemAction.AUTO_ROTATE_OFF)

            // ===== UI 自动化（无障碍） =====
            isUiPressBackCommand(cleaned) -> Command.UiPressBack
            isUiPressHomeCommand(cleaned) -> Command.UiPressHome
            uiScroll != null -> uiScroll

            // ===== 导航 / 外卖 / App内操作 =====
            navigate != null -> navigate
            isTakeoutCommand(cleaned) -> Command.OpenTakeout
            webSearch != null -> webSearch
            appAction != null -> appAction

            // "点击XX"放在外卖/AppAction 之后，避免"点外卖"被误解析
            uiTapText != null -> uiTapText

            // "输入XX"
            uiInputText != null -> uiInputText

            // ===== 打开/关闭 App（兜底） =====
            else -> parseAppCommand(cleaned) ?: Command.Unknown(userInput)
        }
    }

    // ===================== 前导语气词清理 =====================

    /** 去掉句首口语修饰词：帮我/请/我想/诶/哎/那个/我说 等 */
    private fun stripLeadingFillers(input: String): String {
        val fillers = listOf(
            "帮我一下", "帮我", "麻烦你", "麻烦", "请你", "请",
            "我想要", "我想", "我要", "给我",
            "能不能帮我", "可不可以帮我", "可以帮我", "能不能", "可不可以", "可以",
            "你帮我", "你给我", "你能不能",
            "我说", "那个", "诶", "哎", "喂",
            "帮忙", "帮个忙", "劳驾"
        )
        var result = input.trim()
        for (filler in fillers.sortedByDescending { it.length }) {
            if (result.startsWith(filler)) {
                result = result.removePrefix(filler).trim()
                break
            }
        }
        return result
    }

    // ===================== 截屏 =====================

    private fun isScreenshotCommand(input: String): Boolean {
        return input.contains("截屏") || input.contains("截图") ||
               input.contains("截个屏") || input.contains("截个图") ||
               input.contains("拍屏幕") || input.contains("screenshot") ||
               input.contains("screen shot")
    }

    // ===================== 睡觉/静音 =====================

    /** "我要睡了" / "睡觉了" / "晚安" / "困了" / "准备睡了" / "熄灯" → 静音 */
    private fun isSleepCommand(input: String): Boolean {
        return input == "我要睡了" || input == "睡觉了" || input == "睡了" ||
               input == "晚安" || input == "困了" || input == "准备睡了" ||
               input == "熄灯" || input == "该睡了" || input == "要睡了" ||
               input.contains("我要睡觉") || input.contains("准备睡觉") ||
               input.contains("睡觉吧") || input.contains("睡了吧") ||
               input == "静音模式" || input == "勿扰模式" || input == "免打扰"
    }

    // ===================== WiFi =====================

    private fun isWifiOnCommand(input: String): Boolean {
        val onAction = input.contains("打开") || input.contains("开启") ||
                       input.contains("连上") || input.contains("连接") ||
                       input.contains("开一下") || input.contains("开开")
        val wifiKw = input.contains("wifi") || input.contains("wi-fi") ||
                     input.contains("无线网") || input.contains("无线") ||
                     input.contains("wlan") || input.contains("网络")
        // "连网" / "开网" 简写
        val shorthand = input == "连网" || input == "开网" || input == "上网"
        return (onAction && wifiKw) || shorthand
    }

    private fun isWifiOffCommand(input: String): Boolean {
        val offAction = input.contains("关闭") || input.contains("关掉") ||
                        input.contains("断开") || input.contains("关一下") ||
                        input.contains("断掉")
        val wifiKw = input.contains("wifi") || input.contains("wi-fi") ||
                     input.contains("无线网") || input.contains("无线") ||
                     input.contains("wlan") || input.contains("网络")
        val shorthand = input == "断网" || input == "关网" || input == "断wifi"
        return (offAction && wifiKw) || shorthand
    }

    // ===================== 蓝牙 =====================

    private fun isBluetoothOnCommand(input: String): Boolean {
        return (input.contains("打开") || input.contains("开启") || input.contains("连上")) &&
               input.contains("蓝牙")
    }

    private fun isBluetoothOffCommand(input: String): Boolean {
        return (input.contains("关闭") || input.contains("关掉") || input.contains("断开")) &&
               input.contains("蓝牙")
    }

    // ===================== 手电筒 =====================

    private fun isFlashlightOnCommand(input: String): Boolean {
        val explicit = (input.contains("打开") || input.contains("开启") || input.contains("开一下")) &&
                       (input.contains("手电筒") || input.contains("闪光灯") ||
                        input.contains("torch") || input.contains("flashlight"))
        // "开灯" / "照亮" / "照一下" 口语
        val colloquial = input == "开灯" || input == "照亮" || input == "照一下" ||
                         input == "开手电" || input == "手电筒开一下"
        return explicit || colloquial
    }

    private fun isFlashlightOffCommand(input: String): Boolean {
        val explicit = (input.contains("关闭") || input.contains("关掉") || input.contains("关一下")) &&
                       (input.contains("手电筒") || input.contains("闪光灯") ||
                        input.contains("torch") || input.contains("flashlight"))
        val colloquial = input == "关灯" || input == "关掉手电" || input == "手电筒关掉"
        return explicit || colloquial
    }

    // ===================== 亮度 =====================

    private fun isBrightnessUpCommand(input: String): Boolean {
        val verbUp = input.contains("调高") || input.contains("增加") ||
                     input.contains("提高") || input.contains("调亮") ||
                     input.contains("亮一点") || input.contains("亮点") ||
                     input.contains("调亮点") || input.contains("亮一些")
        val kw = input.contains("亮度") || input.contains("屏幕") ||
                 input.contains("brightness") || input.contains("背光")
        // "太暗了" / "看不清" / "屏幕太暗" → 亮一点
        val colloquialUp = input == "太暗了" || input == "看不清" ||
                           input.contains("屏幕太暗") || input.contains("太黑了") ||
                           input == "亮一点" || input == "亮点"
        return (verbUp && kw) || colloquialUp
    }

    private fun isBrightnessDownCommand(input: String): Boolean {
        val verbDown = input.contains("调低") || input.contains("降低") ||
                       input.contains("调暗") || input.contains("减小") ||
                       input.contains("暗一点") || input.contains("暗一些") ||
                       input.contains("调暗点")
        val kw = input.contains("亮度") || input.contains("屏幕") ||
                 input.contains("brightness") || input.contains("背光")
        // "太亮了" / "刺眼" / "亮瞎了" / "屏幕太亮" → 暗一点
        val colloquialDown = input == "太亮了" || input == "刺眼" ||
                             input == "亮瞎了" || input.contains("屏幕太亮") ||
                             input == "暗一点" || input == "暗点"
        return (verbDown && kw) || colloquialDown
    }

    // ===================== 音量 =====================

    private fun isVolume50Command(input: String): Boolean {
        return (input.contains("音量") || input.contains("声音")) &&
               (input.contains("50%") || input.contains("50％") ||
                input.contains("一半") || input.contains("适中") ||
                input == "音量50")
    }

    private fun isVolumeMuteCommand(input: String): Boolean {
        return input.contains("静音") || input.contains("mute") ||
               input.contains("无声") || input.contains("音量为0") ||
               input == "别出声" || input == "安静" || input == "小声"
    }

    private fun isVolumeUpCommand(input: String): Boolean {
        val verbUp = input.contains("调高") || input.contains("增加") ||
                     input.contains("提高") || input.contains("调大") ||
                     input.contains("大声") || input.contains("大点") ||
                     input.contains("大一点") || input.contains("大一些") ||
                     input.contains("响一点") || input.contains("响一些")
        val kw = input.contains("音量") || input.contains("声音") ||
                 input.contains("volume") || input.contains("动静")
        // "听不见" / "太小声了" / "声音太小" / "没声音"
        val colloquialUp = input == "听不见" || input == "太小声了" ||
                           input.contains("声音太小") || input.contains("声音太小了") ||
                           input == "没声音" || input == "大点声" ||
                           input == "大声点" || input == "声音大点" ||
                           input == "音量大点" || input == "开大一点"
        return (verbUp && kw) || colloquialUp
    }

    private fun isVolumeDownCommand(input: String): Boolean {
        val verbDown = input.contains("调低") || input.contains("降低") ||
                       input.contains("减小") || input.contains("调小") ||
                       input.contains("小声") || input.contains("小点") ||
                       input.contains("小一点") || input.contains("小一些") ||
                       input.contains("轻一点") || input.contains("轻一些")
        val kw = input.contains("音量") || input.contains("声音") ||
                 input.contains("volume") || input.contains("动静")
        // "太吵了" / "太大声了" / "声音太大"
        val colloquialDown = input == "太吵了" || input == "太大声了" ||
                             input.contains("声音太大") || input.contains("声音太大了") ||
                             input == "小点声" || input == "小声点" ||
                             input == "声音小点" || input == "音量小点" ||
                             input == "开小一点"
        return (verbDown && kw) || colloquialDown
    }

    // ===================== 自动旋转 =====================

    private fun isAutoRotateOnCommand(input: String): Boolean {
        return (input.contains("打开") || input.contains("开启") || input.contains("允许")) &&
               (input.contains("自动旋转") || input.contains("旋转屏幕") || input.contains("横屏"))
    }

    private fun isAutoRotateOffCommand(input: String): Boolean {
        return (input.contains("关闭") || input.contains("关掉") || input.contains("锁定")) &&
               (input.contains("自动旋转") || input.contains("旋转屏幕") || input.contains("竖屏"))
    }

    // ===================== UI 自动化指令 =====================

    /** "返回" / "后退" / "返回上一页" / "退回去" / "退一下" / "back" */
    private fun isUiPressBackCommand(input: String): Boolean {
        return input.contains("返回") || input.contains("后退") ||
               input == "back" || input == "退回去" || input == "退一下" ||
               input == "返回上一页" || input == "上一步" || input == "返回去"
    }

    /** "回到桌面" / "回桌面" / "回主屏" / "回主页" / "回主屏幕" / "回主页" / "home" / "桌面" */
    private fun isUiPressHomeCommand(input: String): Boolean {
        return input.contains("回到桌面") || input.contains("回桌面") ||
               input.contains("回主屏") || input.contains("回主页") ||
               input.contains("回主屏幕") || input.contains("回到主页") ||
               input.contains("回到主屏") || input == "home" ||
               input == "桌面" || input == "回桌面"
    }

    /**
     * 解析滚动方向，支持丰富的口语表达：
     * - 上滑/向上滑/往上滑/向上滚动/上翻/往上/翻上去
     * - 下滑/向下滑/往下滑/向下滚动/下翻/往下/翻下来
     * - 左滑/向左滑/往左滑
     * - 右滑/向右滑/往右滑
     * - 翻页/下一页/下一页 → 下滚
     */
    private fun parseUiScroll(input: String): Command.UiScroll? {
        val direction = when {
            input.contains("上滑") || input.contains("向上滑") || input.contains("往上滑") ||
            input.contains("向上滚动") || input.contains("往上滚动") ||
            input.contains("上翻") || input.contains("往上") || input.contains("翻上去") ||
            input.contains("向上翻") -> ScrollDirection.UP

            input.contains("下滑") || input.contains("向下滑") || input.contains("往下滑") ||
            input.contains("向下滚动") || input.contains("往下滚动") ||
            input.contains("下翻") || input.contains("往下") || input.contains("翻下来") ||
            input.contains("向下翻") || input.contains("翻页") ||
            input.contains("下一页") || input.contains("往下翻") -> ScrollDirection.DOWN

            input.contains("左滑") || input.contains("向左滑") || input.contains("往左滑") ||
            input.contains("向左滚动") || input.contains("往左") -> ScrollDirection.LEFT

            input.contains("右滑") || input.contains("向右滑") || input.contains("往右滑") ||
            input.contains("向右滚动") || input.contains("往右") -> ScrollDirection.RIGHT

            else -> return null
        }
        return Command.UiScroll(direction)
    }

    /**
     * 解析文本点击："点击XX" / "点一下XX" / "点按XX" / "按下XX" / "选择XX" / "选XX"。
     * 只用明确的动词词头，单字"点"不参与（"点外卖"由 takeout 分支优先处理）。
     */
    private fun parseUiTapText(input: String): Command.UiTapText? {
        val tailRegex = Regex("(一下|吧|了|可以吗|好吗|呗|啦|啊|哦|呀|咯|嘛)\$")
        for (verb in listOf("点击", "点一下", "点按", "按下", "按一下", "选择", "选一下", "点选")) {
            if (input.startsWith(verb)) {
                val target = input.removePrefix(verb).replace(tailRegex, "").trim()
                if (target.isNotEmpty()) {
                    return Command.UiTapText(target)
                }
            }
        }
        // "选XX" 单独处理（避免"选项"误伤，要求后面跟具体内容）
        if (input.startsWith("选") && input.length > 1) {
            val target = input.removePrefix("选").replace(tailRegex, "").trim()
            if (target.isNotEmpty() && !target.startsWith("项")) {
                return Command.UiTapText(target)
            }
        }
        return null
    }

    /**
     * 解析文本输入："输入XXX" / "打字XXX" / "写XXX" / "输入文字XXX"。
     * 通过无障碍服务向当前焦点输入框注入文本。
     */
    private fun parseUiInputText(input: String): Command.UiInputText? {
        val tailRegex = Regex("(吧|了|可以吗|好吗|呗|啦|啊|哦|呀)\$")
        for (verb in listOf("输入", "打字", "输入文字", "写上", "填入", "填写")) {
            if (input.startsWith(verb)) {
                val text = input.removePrefix(verb).replace(tailRegex, "").trim()
                if (text.isNotEmpty()) {
                    return Command.UiInputText(text)
                }
            }
        }
        return null
    }

    /**
     * 解析导航指令："导航到XXX" / "导航至XXX" / "导航去XXX" / "前往XXX" / "去XXX" / "导航XXX"。
     */
    private fun parseNavigate(input: String): Command.Navigate? {
        val prefixes = listOf("导航到", "导航至", "导航去", "前往", "带我去", "导航", "去")
        for (prefix in prefixes) {
            if (input.startsWith(prefix)) {
                val dest = input.removePrefix(prefix).trim()
                if (dest.isNotEmpty()) {
                    return Command.Navigate(dest)
                }
            }
        }
        return null
    }

    /** 解析外卖指令："点外卖" / "叫外卖" / "订外卖" / "打开外卖" / "开外卖" / "点个外卖" */
    private fun isTakeoutCommand(input: String): Boolean {
        return input.contains("外卖") && (
            input.contains("点") || input.contains("叫") || input.contains("订") ||
                input.contains("打开") || input.contains("开启") || input.contains("开") ||
                input.contains("来个") || input.contains("来点"))
    }

    // ===================== 相机/拍照 =====================

    private fun isCameraCommand(input: String): Boolean {
        return input == "拍照" || input == "照相" || input == "拍个照" ||
               input == "拍张照" || input == "来一张" || input == "相机" ||
               input.contains("打开相机") || input.contains("开启相机") ||
               input.contains("启动相机") || input == "拍照片" || input == "拍一下"
    }

    // ===================== 播放音乐 =====================

    private fun isPlayMusicCommand(input: String): Boolean {
        return input == "播放音乐" || input == "听歌" || input == "放首歌" ||
               input == "放点音乐" || input == "来首歌" || input == "来首音乐" ||
               input == "放音乐" || input == "放歌" || input == "听音乐" ||
               input == "播放歌曲" || input == "来歌" || input == "music"
    }

    // ===================== 网页搜索 =====================

    private fun parseWebSearch(input: String): Command.WebSearch? {
        val patterns = listOf(
            Regex("搜索(.+)"),
            Regex("搜一下(.+)"),
            Regex("搜搜(.+)"),
            Regex("查一下(.+)"),
            Regex("查查(.+)"),
            Regex("百度一下(.+)"),
            Regex("百度(.+)"),
            Regex("google(.+)"),
            Regex("搜索一下(.+)"),
            Regex("帮我搜(.+)"),
            Regex("帮我查(.+)")
        )
        for (pattern in patterns) {
            val m = pattern.find(input) ?: continue
            val query = m.groupValues.getOrNull(1)?.trim().orEmpty()
            if (query.isNotEmpty()) {
                return Command.WebSearch(query)
            }
        }
        return null
    }

    // ===================== 闹钟 =====================

    private fun parseSetAlarm(input: String): Command.SetAlarm? {
        // "定闹钟" / "设闹钟" / "设置闹钟" / "闹钟" / "新建闹钟"
        if (!input.contains("闹钟") && !input.contains("闹铃")) return null

        // 尝试提取时间："XX点XX分" / "XX点" / "XX:XX"
        val timePattern1 = Regex("(\\d{1,2})[点时:：](\\d{1,2})分?")
        val timePattern2 = Regex("(\\d{1,2})[点时]")
        val timePattern3 = Regex("(\\d{1,2}):(\\d{2})")

        timePattern1.find(input)?.let { m ->
            val hour = m.groupValues[1].toIntOrNull()
            val minute = m.groupValues[2].toIntOrNull()
            if (hour != null && minute != null) {
                return Command.SetAlarm(hour, minute)
            }
        }
        timePattern3.find(input)?.let { m ->
            val hour = m.groupValues[1].toIntOrNull()
            val minute = m.groupValues[2].toIntOrNull()
            if (hour != null && minute != null) {
                return Command.SetAlarm(hour, minute)
            }
        }
        timePattern2.find(input)?.let { m ->
            val hour = m.groupValues[1].toIntOrNull()
            if (hour != null) {
                return Command.SetAlarm(hour, 0)
            }
        }

        // 没有具体时间，打开闹钟列表
        if (input.contains("设") || input.contains("定") || input.contains("打开") ||
            input.contains("新建") || input == "闹钟" || input == "闹铃") {
            return Command.SetAlarm(null, 0)
        }
        return null
    }

    // ===================== 拨打电话 =====================

    private fun parseMakeCall(input: String): Command.MakeCall? {
        val patterns = listOf(
            Regex("打电话给(.+)"),
            Regex("给(.+)打电话"),
            Regex("拨打(.+)"),
            Regex("拨(.+)的电话"),
            Regex("呼叫(.+)"),
            Regex("打电话给(.+)"),
            Regex("联系(.+)")
        )
        for (pattern in patterns) {
            val m = pattern.find(input) ?: continue
            val target = m.groupValues.getOrNull(1)?.trim().orEmpty()
            if (target.isNotEmpty()) {
                return Command.MakeCall(target)
            }
        }
        return null
    }

    // ===================== 发送短信 =====================

    private fun parseSendSms(input: String): Command.SendSms? {
        val patterns = listOf(
            Regex("发短信给(.+?)说(.+)"),
            Regex("给(.+?)发短信说(.+)"),
            Regex("发信息给(.+?)说(.+)"),
            Regex("短信发给(.+?)说(.+)"),
            Regex("发短信给(.+)"),
            Regex("给(.+)发短信")
        )
        for (pattern in patterns) {
            val m = pattern.find(input) ?: continue
            val target = m.groupValues.getOrNull(1)?.trim().orEmpty()
            val message = m.groupValues.getOrNull(2)?.trim().orEmpty()
            if (target.isNotEmpty()) {
                return Command.SendSms(target, message)
            }
        }
        return null
    }

    // ======================================================================
    //  三大复合场景解析：OrderTakeout · SendChatMessage · CallRide
    // ======================================================================

    // ---------------- 1. 点外卖 ----------------
    // 支持口语：
    //   我想点外卖 → 无关键词
    //   我想点杯奶茶 / 点个肯德基 / 点麦当劳的汉堡 / 点一份酸菜鱼送到公司
    private fun parseOrderTakeout(input: String): Command.OrderTakeout? {
        val tailCut = input.replace(Regex("(吧|呗|啊|啦|好吗|可以吗|好不好|行不|行不行|呢|呀|哦|嘛|咯)\\s*$"), "")

        // 入口动词列表（任一命中则进入外卖场景，避免"点"被 uiTapText 拿走）
        val takeoutEntry = Regex("""
            (?:^|\s|，|。|？|!)
            (点个|点一份|点一客|点|叫个|叫一份|叫|订个|订一份|订|
             下单|来点|点些|点些什么吃的|点些吃的|点东西吃|点吃的|
             想吃个|想吃一份|想吃|要个|要一份|要点|要吃)
            (.+)
        """.trimIndent().replace("\\s+".toRegex(), ""), RegexOption.COMMENTS)

        val prefixEntry = Regex("^(点外卖|叫外卖|订外卖|外卖)$")

        if (prefixEntry.matches(tailCut)) {
            return Command.OrderTakeout()
        }

        val m = takeoutEntry.find(tailCut) ?: return null
        val keywordRaw = m.groupValues.getOrNull(2)?.trim().orEmpty()
        if (keywordRaw.isEmpty()) return Command.OrderTakeout()

        // 关键词段里可能包含 "送到XXX"，先拆地址
        val addrMatch = Regex(""".+(?:送到|送到家|送公司|送到|地址写|送(?!至)(?!往))(.+)$""").find(keywordRaw)
        val addressHint = addrMatch?.groupValues?.getOrNull(1)?.trim()?.takeUnless { it.isEmpty() }

        val foodPart = if (addrMatch != null) keywordRaw.substring(0, addrMatch.range.first).trim() else keywordRaw
            .replace(Regex("""送到.+$"""), "").trim()

        // 在 foodPart 里，如果出现 "...的/家的/里的/店铺的..."，前面是商家，后面是菜品；
        // 否则如果词里有明显商家名（品牌），全部作为 restaurant 给 SystemControl 用搜索。
        val shopSplit = Regex("""(.+?)(?:家的|店里的|店的|的|这里的|那边的)(.+)""").find(foodPart)

        return if (shopSplit != null) {
            val shop = shopSplit.groupValues[1].trim()
            val food = shopSplit.groupValues[2].trim()
            Command.OrderTakeout(
                foodKeyword = food.ifEmpty { null },
                restaurant = shop.ifEmpty { null },
                addressHint = addressHint
            )
        } else {
            // 没有区分：整体作为 foodKeyword（SystemControl 里搜关键词会同时搜商家和菜）
            Command.OrderTakeout(
                foodKeyword = foodPart.ifEmpty { null },
                restaurant = null,
                addressHint = addressHint
            )
        }
    }

    // ---------------- 2. 给妈发信息说今晚不回去了（微信/QQ/短信） ----------------
    // 口语：
    //   给我妈发微信说今晚不回去了
    //   用QQ告诉张三我已经到了
    //   给妈妈发条短信说我晚点到家
    //   告诉爸爸今晚要加班（渠道 UNKNOWN → 微信→QQ→短信 依次试）
    private fun parseSendChatMessage(input: String): Command.SendChatMessage? {
        // 正则按优先级，先匹配有渠道的（Pair = 正则 + 渠道枚举）
        val patternsWithChannel: List<Pair<Regex, ChatChannel>> = listOf(
            Regex("给(.+?)发(?:微信|微信消息|wx)说(.+)") to ChatChannel.WECHAT,
            Regex("用(?:微信|wx)给(.+?)说(.+)") to ChatChannel.WECHAT,
            Regex("用(?:微信|wx)发消息给(.+?)说(.+)") to ChatChannel.WECHAT,
            Regex("给(.+?)发(?:qq|QQ|扣扣|消息)说(.+)") to ChatChannel.QQ,
            Regex("用(?:qq|QQ|扣扣)给(.+?)说(.+)") to ChatChannel.QQ,
            Regex("给(.+?)发短信说(.+)") to ChatChannel.SMS
        )

        for ((regex, ch) in patternsWithChannel) {
            val m = regex.find(input) ?: continue
            val who = m.groupValues[1].trim()
            val what = m.groupValues[2].trim()
            if (who.isNotEmpty() && what.isNotEmpty()) {
                return Command.SendChatMessage(
                    contactNameOrPhone = who,
                    message = what,
                    channel = ch
                )
            }
        }

        // 没有指定渠道：告诉/告知/和...说/跟...说 这种 → UNKNOWN（微信→QQ→短信 兜底）
        val noChannelPatterns = listOf(
            Regex("告诉(.+?)(?:我)?(说|一下|声)(.+)"),
            Regex("和(.+?)说(.+)"),
            Regex("跟(.+?)说(.+)"),
            Regex("给(.+?)说(.+)"),
            Regex("通知(.+?)(?:一下|一声)?说(.+)")
        )
        for (regex in noChannelPatterns) {
            val m = regex.find(input) ?: continue
            val who = m.groupValues[1].trim()
            val what = (runCatching { m.groupValues[3] }.getOrNull() ?: m.groupValues[2]).trim()
            if (who.isNotEmpty() && what.isNotEmpty()) {
                return Command.SendChatMessage(
                    contactNameOrPhone = who,
                    message = what,
                    channel = ChatChannel.UNKNOWN
                )
            }
        }
        return null
    }

    // ---------------- 3. 打车去哪 ----------------
    // 口语：
    //   打车去机场
    //   叫个车去北京西站
    //   打快车去公司
    //   叫辆车到西湖
    //   帮我叫辆出租车去首都机场T3
    private fun parseCallRide(input: String): Command.CallRide? {
        val patterns = listOf(
            Regex("打(快车|专车|拼车|顺风车|出租车|车|的|的士)(?:去|到|前往|往|去一下|去到)(.+)"),
            Regex("叫(?:一|个|辆)?(快车|专车|拼车|顺风车|出租车|车|辆|部)?(?:去|到|前往|往|去一下|去到)(.+)"),
            Regex("叫车(?:去|到|前往|往)(.+)"),
            Regex("打车(?:去|到|前往|往|一下)(.+)"),
            Regex("滴滴去(.+)"),
            Regex("(?:打|叫)(?:个|辆|一部)?车(.+)")  // 兜底："打车去机场" → "去机场"也能命中
        )

        for (regex in patterns) {
            val m = regex.find(input) ?: continue
            // 不同正则 group 数不同：有车型的 group[1]=车型, group[2]=目的地；无车型的 group[1]=目的地
            val groups = m.groupValues.drop(1).mapNotNull { g -> g.trim().ifEmpty { null } }
            val carTypePref: String?
            val destination: String
            when (groups.size) {
                2 -> {
                    carTypePref = groups[0].takeIf { it in listOf("快车","专车","拼车","顺风车","出租车","的士","的") }
                    destination = groups[1]
                }
                1 -> {
                    carTypePref = null
                    destination = groups[0]
                }
                else -> continue
            }
            // 清洗目的地前导的方向词 "去/到/前往"（防止兜底正则没剥离干净）
            val cleanDest = destination
                .replace(Regex("""^(去|到|前往|往|去一下|去到)\s*"""), "")
                .replace(Regex("""(吧|好吗|可以吗|呗|啊|啦|呢|呀)\s*$"""), "")
                .trim()
            if (cleanDest.isNotEmpty()) {
                return Command.CallRide(
                    destination = cleanDest,
                    carTypePref = carTypePref
                )
            }
        }
        return null
    }

    /**
     * 解析 App 内操作指令，支持发送消息：
     * "在微信里发消息给XXX" / "用微信发消息给XXX" / "在微信给XXX发消息" /
     * "微信发消息给XXX" / "给XXX发微信消息"
     */
    private fun parseAppAction(input: String): Command.AppAction? {
        val patterns = listOf(
            Regex("在(.+?)(?:里|中|上)发消息给(.+)"),
            Regex("用(.+?)发消息给(.+)"),
            Regex("在(.+?)给(.+?)发消息"),
            Regex("(.+?)发消息给(.+)"),
            Regex("给(.+?)(?:发|发送)(?:微信|qq|消息)")
        )
        for (pattern in patterns) {
            val m = pattern.find(input) ?: continue
            val g1 = m.groupValues.getOrNull(1)?.trim().orEmpty()
            val g2 = m.groupValues.getOrNull(2)?.trim().orEmpty()
            // 判断哪个是 app 名，哪个是联系人
            val appName: String
            val contact: String
            if (pattern.pattern.contains("微信|qq|消息") && g1.isNotEmpty() && g2.isEmpty()) {
                // "给XXX发微信" → g1=联系人, app=微信
                appName = "微信"
                contact = g1
            } else if (g1.isNotEmpty() && g2.isNotEmpty()) {
                appName = g1
                contact = g2
            } else continue
            if (appName.isNotEmpty() && contact.isNotEmpty()) {
                return Command.AppAction(
                    appName = appName,
                    action = "send_message",
                    params = mapOf("contact" to contact)
                )
            }
        }
        return null
    }

    // ===================== 打开/关闭 App =====================

    private fun parseAppCommand(input: String): Command? {
        val openActions = listOf("打开", "启动", "开启", "运行", "进一下", "进", "切到", "用一下", "用用")
        val closeActions = listOf("关闭", "退出", "关掉", "关了", "关掉")

        val tailRegex = Regex("(一下|吧|了|可以吗|好吗|呗|啦|啊|哦|呀|咯|嘛)\$")

        for (action in openActions) {
            if (input.contains(action)) {
                val after = input.substringAfter(action).trim()
                val appName = after.replace(tailRegex, "").trim()
                if (appName.isNotEmpty()) {
                    val packageName = getPackageNameByAppName(appName)
                    return Command.OpenApp(appName = appName, packageName = packageName)
                }
            }
        }

        for (action in closeActions) {
            if (input.contains(action)) {
                val after = input.substringAfter(action).trim()
                val appName = after.replace(tailRegex, "").trim()
                if (appName.isNotEmpty()) {
                    return Command.CloseApp(appName = appName)
                }
            }
        }

        // 游戏/短视频口语："玩XX" / "刷XX"
        val playActions = listOf("玩", "刷", "看")
        for (action in playActions) {
            if (input.startsWith(action) && input.length > action.length + 1) {
                val after = input.removePrefix(action).trim()
                val appName = after.replace(tailRegex, "").trim()
                if (appName.isNotEmpty() && !appName.startsWith("一")) {
                    val packageName = getPackageNameByAppName(appName)
                    if (packageName.isNotEmpty()) {
                        return Command.OpenApp(appName = appName, packageName = packageName)
                    }
                }
            }
        }

        // 英文动作
        val enOpen = listOf("open", "launch", "start")
        val enClose = listOf("close", "exit", "quit")
        for (action in enOpen) {
            if (input.startsWith(action) || input.contains(" $action ")) {
                val appName = input.substringAfter(action).trim()
                    .replace(Regex("(please|plz)"), "").trim()
                if (appName.isNotEmpty()) {
                    val packageName = getPackageNameByAppName(appName)
                    return Command.OpenApp(appName = appName, packageName = packageName)
                }
            }
        }
        for (action in enClose) {
            if (input.startsWith(action) || input.contains(" $action ")) {
                val appName = input.substringAfter(action).trim()
                if (appName.isNotEmpty()) {
                    return Command.CloseApp(appName = appName)
                }
            }
        }

        return null
    }

    /** 包名解析委托给 [ISystemControl]，统一维护映射表 */
    private fun getPackageNameByAppName(appName: String): String =
        systemControl.getPackageNameByAppName(appName)

    // ========= Day3-1：示例池（给 Unknown 做相似推荐） =========
    companion object {
        val CANONICAL_EXAMPLES: List<String> = listOf(
            // 系统控制
            "打开WiFi",
            "关闭WiFi",
            "打开蓝牙",
            "亮度调高一点",
            "亮度调低一点",
            "音量调到50%",
            "音量大一点",
            "音量小一点",
            "开手电筒",
            "关掉手电筒",
            "开启自动旋转",
            "手机调成静音",
            // 截屏/拍照/多媒体
            "截个屏",
            "帮我拍照",
            "放首歌",
            "打开音乐播放器",
            // 导航 & 外卖
            "导航去天安门",
            "帮我打开外卖软件",
            "点个肯德基",
            "点份酸菜鱼送到公司",
            // 复合场景（三大场景）
            "给我妈发微信说今晚不回去了",
            "用QQ告诉张三我已经到了",
            "给妈妈发短信说我晚点到家",
            "打车去机场",
            "叫个车去北京西站",
            "打快车去公司",
            // App 操作
            "打开微信",
            "关闭抖音",
            "搜索北京今天天气",
            "帮我搜索附近的咖啡店",
            "定个明早7点的闹钟",
            "新建闹钟下午3点",
            "打给爸爸",
            "给10086打电话",
            "给10086发短信说查流量",
            // UI 自动化
            "点击确定按钮",
            "点击返回上一页",
            "上滑一下",
            "输入我的名字是张三"
        )
    }
}
