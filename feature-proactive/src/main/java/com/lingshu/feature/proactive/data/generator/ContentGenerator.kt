package com.lingshu.feature.proactive.data.generator

import com.lingshu.feature.proactive.domain.TriggerType

data class NotificationContent(
    val title: String,
    val content: String,
    val actionText: String = "知道了"
)

class ContentGenerator {

    fun generate(triggerType: TriggerType): NotificationContent {
        return when (triggerType) {
            TriggerType.LATE_NIGHT -> NotificationContent(
                title = "夜深了，该休息了",
                content = "已经很晚了，早点休息对身体好哦～明天还要继续加油呢！",
                actionText = "好的，这就睡"
            )
            TriggerType.MEAL_TIME -> NotificationContent(
                title = "该吃饭啦",
                content = "饭点到了，记得按时吃饭哦！规律饮食对肠胃好～",
                actionText = "这就去吃"
            )
            TriggerType.SEDENTARY -> NotificationContent(
                title = "起来活动一下吧",
                content = "你已经坐了很久了，起来走动走动，伸伸懒腰吧～",
                actionText = "好的，动一动"
            )
            TriggerType.DARK_WALKING -> NotificationContent(
                title = "光线有点暗哦",
                content = "环境光线较暗，行走时注意安全，要不要打开手电筒？",
                actionText = "打开手电筒"
            )
            TriggerType.HEART_RATE -> NotificationContent(
                title = "心率有点异常",
                content = "检测到心率不太正常，注意休息，如有不适请及时就医。",
                actionText = "我知道了"
            )
            TriggerType.STRESS -> NotificationContent(
                title = "压力有点大哦",
                content = "感觉你最近压力挺大的，试着深呼吸放松一下吧～",
                actionText = "试试放松"
            )
            TriggerType.RAINY_DAY -> NotificationContent(
                title = "今天可能会下雨",
                content = "今天降水概率较高，出门记得带伞哦！",
                actionText = "知道啦"
            )
        }
    }
}
