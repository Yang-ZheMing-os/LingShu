package com.lingshu.feature.control.domain.scenes

import com.lingshu.feature.control.domain.ChatChannel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通用场景框架内置的「三大场景」定义：
 *  1. SEND_CHAT_SCENE —— 给 X 发消息（微信/QQ/短信/飞书等）
 *  2. CALL_RIDE_SCENE —— 打车去 X（可选择车型偏好）
 *  3. NAV_SCENE —— 导航去 X
 *  4. ORDER_TAKEOUT_SCENE —— 点个 X 外卖（可选送到哪里）
 *
 * 后续新增业务=往 [builtIns] 列表里加 GenericScene，或者让用户通过 ISceneRepository.upsertCustom 动态加入。
 */
@Singleton
class BuiltInScenes @Inject constructor() {

    val builtIns: List<GenericScene> by lazy {
        listOf(
            SEND_CHAT_SCENE, CALL_RIDE_SCENE, NAV_SCENE, ORDER_TAKEOUT_SCENE,
            MAKE_CALL_SCENE, SEND_SMS_SCENE, SET_ALARM_SCENE,
            TAKE_PHOTO_SCENE, WEB_SEARCH_SCENE,
            OPEN_APP_SCENE, CLOSE_APP_SCENE, PLAY_MUSIC_SCENE
        )
    }

    companion object {
        val SEND_CHAT_SCENE: GenericScene = GenericScene(
            sceneId = "builtin_send_chat",
            displayName = "给 X 发消息",
            builtIn = true,
            priority = 100,
            intentKeywords = listOf(
                "发短信", "发微信", "发qq", "发飞书", "告诉", "通知", "和", "给",
                "send", "text", "message", "tell", "whatsapp", "wechat"
            ),
            slots = listOf(
                SlotSpec(
                    name = "channel",
                    askPrompt = "用哪个应用发？（微信 / QQ / 短信）",
                    defaultValue = ChatChannel.WECHAT.name
                ),
                SlotSpec(
                    name = "contact",
                    askPrompt = "发给谁呀？可以说「妈妈」「张三」这种联系人名字或号码"
                ),
                SlotSpec(
                    name = "message",
                    askPrompt = "想发的内容是什么？"
                )
            ),
            steps = listOf(
                SceneStep(
                    stepId = "open_app",
                    action = StepActionType.OPEN_APP,
                    humanLabel = "打开对应 App",
                    slotBindings = mapOf("appName" to "{channel}")
                ),
                SceneStep(
                    stepId = "send_msg",
                    action = StepActionType.SEND_CHAT_MESSAGE,
                    humanLabel = "发送消息给 {contact}",
                    slotBindings = mapOf(
                        "channel" to "{channel}",
                        "contact" to "{contact}",
                        "message" to "{message}"
                    )
                )
            ),
            completionText = "已经帮你通过 {channel} 给 {contact} 发送了：\n「{message}」✅"
        )

        val CALL_RIDE_SCENE: GenericScene = GenericScene(
            sceneId = "builtin_call_ride",
            displayName = "打车去 X",
            builtIn = true,
            priority = 90,
            intentKeywords = listOf(
                "打车", "叫车", "约车", "打一辆", "打个车", "打快车", "打专车", "打出租", "ride", "taxi"
            ),
            slots = listOf(
                SlotSpec(
                    name = "destination",
                    askPrompt = "要去哪里？"
                ),
                SlotSpec(
                    name = "carType",
                    askPrompt = "有车型偏好吗？（快车/专车/拼车/顺风车/出租车，不选就默认快车）",
                    optional = true
                )
            ),
            steps = listOf(
                SceneStep(
                    stepId = "ride",
                    action = StepActionType.CALL_RIDE,
                    humanLabel = "发起打车请求 → {destination}",
                    slotBindings = mapOf(
                        "destination" to "{destination}",
                        "carType" to "{carType}"
                    )
                )
            ),
            completionText = "已为你呼叫 {carType 或 快车}，目的地：{destination}，司机接单后会通知你 🚕"
        )

        val NAV_SCENE: GenericScene = GenericScene(
            sceneId = "builtin_nav",
            displayName = "导航去 X",
            builtIn = true,
            priority = 80,
            intentKeywords = listOf(
                "导航", "去…", "带我去", "指路", "navigate", "directions to", "高德导航", "百度导航"
            ),
            slots = listOf(
                SlotSpec(
                    name = "destination",
                    askPrompt = "要导航去哪里？"
                )
            ),
            steps = listOf(
                SceneStep(
                    stepId = "open_map",
                    action = StepActionType.OPEN_APP,
                    humanLabel = "打开地图 App",
                    slotBindings = mapOf("appName" to "高德地图")
                ),
                SceneStep(
                    stepId = "nav",
                    action = StepActionType.NAVIGATE,
                    humanLabel = "发起导航 → {destination}",
                    slotBindings = mapOf("destination" to "{destination}")
                )
            ),
            completionText = "已开始导航：{destination} 🧭"
        )

        val ORDER_TAKEOUT_SCENE: GenericScene = GenericScene(
            sceneId = "builtin_order_takeout",
            displayName = "点外卖 / 点餐",
            builtIn = true,
            priority = 70,
            intentKeywords = listOf(
                "点外卖", "点餐", "订餐", "订外卖", "买个", "点个", "外卖",
                "order takeout", "deliver", "order food"
            ),
            slots = listOf(
                SlotSpec(
                    name = "food",
                    askPrompt = "想吃点什么？（例：肯德基全家桶 / 一杯奶茶 / 酸菜鱼）"
                ),
                SlotSpec(
                    name = "address",
                    askPrompt = "送到哪里？（不填就默认地址）",
                    optional = true
                )
            ),
            steps = listOf(
                SceneStep(
                    stepId = "open_takeout",
                    action = StepActionType.OPEN_TAKEOUT,
                    humanLabel = "打开外卖 App"
                ),
                SceneStep(
                    stepId = "order",
                    action = StepActionType.ORDER_TAKEOUT,
                    humanLabel = "下单：{food}",
                    slotBindings = mapOf(
                        "food" to "{food}",
                        "address" to "{address}"
                    )
                )
            ),
            completionText = "已为你下单：{food}（{address 或 默认地址}），骑手接单后会通知你 🥡"
        )

        val MAKE_CALL_SCENE: GenericScene = GenericScene(
            sceneId = "builtin_make_call",
            displayName = "打电话给 X",
            builtIn = true,
            priority = 95,
            intentKeywords = listOf(
                "打电话", "打给", "呼叫", "拨打", "给…打电话", "给…拨个电话",
                "call", "phone", "dial", "ring"
            ),
            slots = listOf(
                SlotSpec(
                    name = "contact",
                    askPrompt = "打给谁呀？可以说「爸爸」「10086」这种联系人或号码"
                )
            ),
            steps = listOf(
                SceneStep(
                    stepId = "dial",
                    action = StepActionType.MAKE_CALL,
                    humanLabel = "拨号 → {contact}",
                    slotBindings = mapOf("contact" to "{contact}")
                )
            ),
            completionText = "正在拨打「{contact}」，请你在系统拨号界面确认呼叫 📞"
        )

        val SEND_SMS_SCENE: GenericScene = GenericScene(
            sceneId = "builtin_send_sms",
            displayName = "给 X 发短信",
            builtIn = true,
            priority = 92,
            intentKeywords = listOf(
                "发短信", "发短信给", "发条短信", "给…发条短信", "给…发短信说",
                "sms", "send sms", "text sms"
            ),
            slots = listOf(
                SlotSpec(
                    name = "contact",
                    askPrompt = "发给谁？"
                ),
                SlotSpec(
                    name = "message",
                    askPrompt = "短信内容是什么？"
                )
            ),
            steps = listOf(
                SceneStep(
                    stepId = "sms",
                    action = StepActionType.SEND_SMS,
                    humanLabel = "给 {contact} 发短信",
                    slotBindings = mapOf(
                        "contact" to "{contact}",
                        "message" to "{message}"
                    )
                )
            ),
            completionText = "已经编辑好短信：\n收件人「{contact}」\n内容「{message}」\n请你最后点一下发送哦 ✉️"
        )

        val SET_ALARM_SCENE: GenericScene = GenericScene(
            sceneId = "builtin_set_alarm",
            displayName = "新建闹钟",
            builtIn = true,
            priority = 85,
            intentKeywords = listOf(
                "定闹钟", "设置闹钟", "新建闹钟", "明天几点", "下午几点", "早上几点", "提醒我",
                "alarm", "set alarm", "remind me"
            ),
            slots = listOf(
                SlotSpec(
                    name = "hour",
                    askPrompt = "几点？（0-23 小时制，比如 7 点就填 7）"
                ),
                SlotSpec(
                    name = "minute",
                    askPrompt = "几分？（0-59，不填就默认 00）",
                    optional = true,
                    defaultValue = "0"
                ),
                SlotSpec(
                    name = "label",
                    askPrompt = "给闹钟起个名字？（可选：起床 / 吃药 / 开会…）",
                    optional = true
                )
            ),
            steps = listOf(
                SceneStep(
                    stepId = "alarm",
                    action = StepActionType.SET_ALARM,
                    humanLabel = "新建闹钟",
                    slotBindings = mapOf(
                        "hour" to "{hour}",
                        "minute" to "{minute}",
                        "label" to "{label}"
                    )
                )
            ),
            completionText = "闹钟已设置到「{hour}:{minute 或 00}」{label 或 } ⏰"
        )

        val TAKE_PHOTO_SCENE: GenericScene = GenericScene(
            sceneId = "builtin_take_photo",
            displayName = "拍张照",
            builtIn = true,
            priority = 82,
            intentKeywords = listOf(
                "拍照", "拍张照", "照张相", "打开相机", "给我拍个照",
                "camera", "take photo", "take a picture"
            ),
            slots = emptyList(),
            steps = listOf(
                SceneStep(
                    stepId = "open_camera",
                    action = StepActionType.OPEN_CAMERA,
                    humanLabel = "打开相机"
                )
            ),
            completionText = "相机已就绪，对准你想拍的按快门就行 📷"
        )

        val WEB_SEARCH_SCENE: GenericScene = GenericScene(
            sceneId = "builtin_web_search",
            displayName = "搜索 XXX",
            builtIn = true,
            priority = 80,
            intentKeywords = listOf(
                "搜索", "搜一下", "查一下", "帮我搜", "搜", "查一查", "查询",
                "search", "google", "baidu", "lookup"
            ),
            slots = listOf(
                SlotSpec(
                    name = "query",
                    askPrompt = "你想搜什么关键词？"
                )
            ),
            steps = listOf(
                SceneStep(
                    stepId = "search",
                    action = StepActionType.WEB_SEARCH,
                    humanLabel = "搜索「{query}」",
                    slotBindings = mapOf("query" to "{query}")
                )
            ),
            completionText = "正在搜索「{query}」，结果马上出来 🔍"
        )

        val OPEN_APP_SCENE: GenericScene = GenericScene(
            sceneId = "builtin_open_app",
            displayName = "打开某个 App",
            builtIn = true,
            priority = 78,
            intentKeywords = listOf(
                "打开", "启动", "点开", "进", "进一下",
                "open", "launch", "start"
            ),
            slots = listOf(
                SlotSpec(
                    name = "appName",
                    askPrompt = "打开哪个 App？（例：微信 / 抖音 / 网易云音乐）"
                )
            ),
            steps = listOf(
                SceneStep(
                    stepId = "open",
                    action = StepActionType.OPEN_APP,
                    humanLabel = "启动「{appName}」",
                    slotBindings = mapOf("appName" to "{appName}")
                )
            ),
            completionText = "已为你打开「{appName}」📱"
        )

        val CLOSE_APP_SCENE: GenericScene = GenericScene(
            sceneId = "builtin_close_app",
            displayName = "关闭某个 App",
            builtIn = true,
            priority = 77,
            intentKeywords = listOf(
                "关闭", "关掉", "退出", "关了", "退掉",
                "close", "kill", "quit"
            ),
            slots = listOf(
                SlotSpec(
                    name = "appName",
                    askPrompt = "关闭哪个 App？"
                )
            ),
            steps = listOf(
                SceneStep(
                    stepId = "close",
                    action = StepActionType.CLOSE_APP,
                    humanLabel = "退出「{appName}」",
                    slotBindings = mapOf("appName" to "{appName}")
                )
            ),
            completionText = "已请求关闭「{appName}」（系统最终是否保留后台由系统决定）🚫"
        )

        val PLAY_MUSIC_SCENE: GenericScene = GenericScene(
            sceneId = "builtin_play_music",
            displayName = "放点音乐",
            builtIn = true,
            priority = 75,
            intentKeywords = listOf(
                "放首歌", "放点音乐", "播放音乐", "唱首歌", "来点歌", "听歌",
                "music", "play music", "play song"
            ),
            slots = emptyList(),
            steps = listOf(
                SceneStep(
                    stepId = "play",
                    action = StepActionType.PLAY_MUSIC,
                    humanLabel = "打开音乐播放"
                )
            ),
            completionText = "音乐已就位，选你喜欢的听就好 🎵"
        )
    }
}
