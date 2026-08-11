package com.lingshu.agent.core.model.routing

/**
 * 意图分类器 — P2 模型路由系统
 *
 * 按规格书实现三类匹配，顺序执行命中即停：
 * - 规则匹配（<100ms）：关键词硬编码匹配
 * - 视觉匹配（<300ms）：图像理解语义 → MiniCPM-V
 * - 对话匹配（>300ms）：兜底 → Gemma 对话
 */
object IntentClassifier {

    /**
     * 分类用户的输入消息并返回意图类型
     *
     * @param text 用户输入文本
     * @param hasImage 是否附带图片
     * @return 分类结果
     */
    fun classify(text: String?, hasImage: Boolean = false): IntentResult {
        val input = text?.trim() ?: ""

        // 1. 规则匹配（<100ms）— 关键词硬编码
        if (input.isNotEmpty()) {
            val ruleResult = ruleMatch(input)
            if (ruleResult != null) {
                return IntentResult(
                    intentType = IntentType.RULE,
                    targetModel = TargetModel.GEMMA,
                    ruleCategory = ruleResult,
                    priority = 1
                )
            }
        }

        // 2. 视觉匹配（<300ms）— 图片理解语义
        if (hasImage || isVisualIntent(input)) {
            return IntentResult(
                intentType = IntentType.VISION,
                targetModel = TargetModel.MINICPM_V,
                priority = 2
            )
        }

        // 3. 对话匹配（>300ms）— 兜底
        return IntentResult(
            intentType = IntentType.CONVERSATION,
            targetModel = TargetModel.GEMMA,
            priority = 3
        )
    }

    // ==================== 规则匹配关键词 ====================

    private fun ruleMatch(text: String): RuleCategory? {
        // WiFi 控制
        if (Regex("(打开|关闭|连接|断开|连上|关掉).{0,4}(WiFi|Wi-Fi|wifi|无线|WLAN)", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return RuleCategory.WIFI_CONTROL
        }

        // 蓝牙控制
        if (Regex("(打开|关闭|连接|断开|连上|关掉).{0,4}(蓝牙|Bluetooth|bluetooth)", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return RuleCategory.BLUETOOTH_CONTROL
        }

        // 手电筒
        if (Regex("(打开|关闭|开启|关掉).{0,2}(手电筒|闪光灯|手电)", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return RuleCategory.FLASHLIGHT
        }

        // 音量控制
        if (Regex("(调|增大|减小|提高|降低|加|减|提高|调大|调小).{0,2}(音量|声音|媒体音量|通话音量)|(静音|静音模式|振动)").containsMatchIn(text)) {
            return RuleCategory.VOLUME_CONTROL
        }

        // 亮度控制
        if (Regex("(调|增大|减小|提高|降低|加|减).{0,2}(亮度|屏幕亮度)|(自动亮度|护眼模式)").containsMatchIn(text)) {
            return RuleCategory.BRIGHTNESS_CONTROL
        }

        // 打开应用
        if (Regex("(打开|启动|运行|进入).{0,8}(应用|APP|app|软件|程序|微信|支付宝|淘宝|抖音|百度|地图|设置|相机|相册|计算器|日历|时钟|音乐|视频)").containsMatchIn(text)) {
            return RuleCategory.OPEN_APP
        }

        // 截屏
        if (Regex("(截屏|截图|屏幕截图|截个图|截一张图)", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return RuleCategory.SCREENSHOT
        }

        return null
    }

    /**
     * 判断是否为视觉意图
     * 包含"这张图/这个图片/这是什么/识别一下/看看/帮我看看" 等 → 视觉匹配
     */
    private fun isVisualIntent(text: String): Boolean {
        if (text.isBlank()) return false
        return Regex(
            "(这张图|这个图片|这张照片|这个照片|这图|这个图|这是什么|识别一下|帮我识别|" +
            "看看这张|看看这个|帮我看看|看一下|看下这张|看下这个|分析这张|分析这个|" +
            "图片里|图里|图中|照片里|截图里|描述一下|描述这张|描述这个|里面是什么|" +
            "上面是什么|这是什么.{0,4}(东西|物品|文字|字|内容|标识|标志)"
        ).containsMatchIn(text)
    }
}

/** 意图类型 */
enum class IntentType {
    /** 规则匹配：设备控制类 */
    RULE,
    /** 视觉匹配：图片理解类 */
    VISION,
    /** 对话匹配：通用聊天类 */
    CONVERSATION
}

/** 目标模型 */
enum class TargetModel(val displayName: String) {
    GEMMA("Gemma 4 E2B"),
    MINICPM_V("MiniCPM-V"),
    QWEN("Qwen"),
    /** 云端 API 兜底 */
    CLOUD_API("云端API")
}

/** 规则分类 */
enum class RuleCategory(val displayName: String) {
    WIFI_CONTROL("WiFi控制"),
    BLUETOOTH_CONTROL("蓝牙控制"),
    FLASHLIGHT("手电筒"),
    VOLUME_CONTROL("音量控制"),
    BRIGHTNESS_CONTROL("亮度控制"),
    OPEN_APP("打开应用"),
    SCREENSHOT("截屏")
}

/** 意图分类结果 */
data class IntentResult(
    val intentType: IntentType,
    val targetModel: TargetModel,
    /** 规则匹配时有效 */
    val ruleCategory: RuleCategory? = null,
    /** 优先级（1最高） */
    val priority: Int
) {
    /** 预估耗时（毫秒） */
    val estimatedLatencyMs: Long
        get() = when (intentType) {
            IntentType.RULE -> 100L
            IntentType.VISION -> 300L
            IntentType.CONVERSATION -> 500L
        }
}
