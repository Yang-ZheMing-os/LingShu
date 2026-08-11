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
    val permissionLevel: PermissionLevel
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
