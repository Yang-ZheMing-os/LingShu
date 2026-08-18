package com.lingshu.feature.mod.domain

data class Mod(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val enabled: Boolean,
    val installedAt: Long = System.currentTimeMillis(),
    val manifest: ModManifest
)

data class ModManifest(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val mainScript: String = "skills/main.js",
    val minAppVersion: String = "1.0.0",
    val permissions: List<String>,
    val permissionLevel: PermissionLevel,
    val category: String = "other",
    val versionCode: Int = 1,
    val dependencies: List<String> = emptyList(),
    val entryPoint: String = "",
    // ======== 声明式能力（JSON 即可生效，无需写 JS）========
    /** 关键词→指令别名：用户说 [关键词] 时填充 [canonicalCommand] 发送 */
    val aliases: List<ModAlias> = emptyList(),
    /** 快捷动作卡片（Chat 底部 Chip / 首页卡片） */
    val quickActions: List<ModQuickAction> = emptyList(),
    /** 人格系统附加提示词（激活后拼到 system prompt 末尾） */
    val personaPrompt: String? = null,
    /** RAG 片段（标题+正文，命中关键词时拼到上下文） */
    val promptSnippets: List<ModPromptSnippet> = emptyList(),
    /** 首页导航卡片 */
    val homeNavCards: List<ModHomeNav> = emptyList()
) {
    companion object {
        /** 根据声明的权限列表，推导该 Mod 的权限级别 */
        fun deriveLevel(permissions: List<String>): PermissionLevel {
            val lower = permissions.map { it.lowercase() }
            val dangerous = listOf("system_api", "root", "shell", "accessibility_dangerous")
            val advanced = listOf("tap", "swipe", "input_text", "accessibility", "ui_control")
            val intermediate = listOf("open_app", "send_notification", "set_alarm", "calendar", "network")

            if (lower.any { it in dangerous }) return PermissionLevel.DANGEROUS
            if (lower.any { it in advanced }) return PermissionLevel.ADVANCED
            if (lower.any { it in intermediate }) return PermissionLevel.INTERMEDIATE
            return PermissionLevel.NORMAL
        }
    }
}

/** 指令别名：用户说 "查天气"/"今天热不热" → 自动发送 "查询北京今天的天气"（或对应 deeplink） */
data class ModAlias(
    val keywords: List<String>,
    val canonicalCommand: String,
    val description: String? = null
)

/** 快捷动作卡片 */
data class ModQuickAction(
    val id: String,
    val label: String,
    val iconEmoji: String? = null,        // 😀🚀 可选 emoji，或 fallback 到 material icon name
    val command: String,                 // 点击后自动发送给灵枢的指令（与用户打字等效）
    val description: String? = null,
    val accentColor: String? = null      // "#FF5A5A" 可选卡片强调色
)

/** RAG 提示片段：当用户输入命中 triggerKeywords 时，把 content 拼进 LLM 上下文 */
data class ModPromptSnippet(
    val triggerKeywords: List<String>,
    val content: String,
    val title: String? = null
)

/** 首页导航卡片（非 Mod 页的通用展示） */
data class ModHomeNav(
    val title: String,
    val desc: String,
    val command: String,                 // 点击后发送的指令
    val accentColor: String? = null
)

data class ModInfo(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val downloadUrl: String,
    val size: Long,
    val permissionLevel: PermissionLevel,
    val rating: Float = 0f,
    val downloads: Int = 0
)

enum class PermissionLevel {
    NORMAL,
    INTERMEDIATE,
    ADVANCED,
    DANGEROUS;

    companion object {
        /** 根据权限列表得到等级（与 ModManifest.deriveLevel 语义一致） */
        fun fromPermissions(permissions: List<String>): PermissionLevel =
            ModManifest.deriveLevel(permissions)
    }
}
