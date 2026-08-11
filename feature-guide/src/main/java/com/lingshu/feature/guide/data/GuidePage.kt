package com.lingshu.feature.guide.data

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Handshake
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.ui.graphics.vector.ImageVector

enum class GuidePage(
    val icon: ImageVector,
    val title: String,
    val description: String
) {
    BRAND_INTRO(
        icon = Icons.Outlined.SmartToy,
        title = "品牌介绍",
        description = "灵枢智能助手，您的专属AI伙伴"
    ),
    CORE_ABILITY(
        icon = Icons.Outlined.AutoAwesome,
        title = "核心能力",
        description = "强大的AI内核，理解您的每一个需求"
    ),
    VOICE_ABILITY(
        icon = Icons.Outlined.GraphicEq,
        title = "语音能力",
        description = "自然流畅的语音交互，解放双手"
    ),
    PHONE_CONTROL(
        icon = Icons.Outlined.Handshake,
        title = "手机控制",
        description = "智能控制手机，操作从未如此简单"
    ),
    PRIVACY_PROMISE(
        icon = Icons.Outlined.Lock,
        title = "隐私承诺",
        description = "端侧处理，您的数据始终安全"
    );

    companion object {
        val pages = values().toList()
        val pageCount = values().size
    }
}
