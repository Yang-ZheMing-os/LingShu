package com.lingshu.agent.core.model

enum class ModCategory {
    PERSONA,
    SKILL,
    THEME,
    AUTOMATION,
    DATA
}

enum class ModSource {
    LOCAL,
    GITHUB,
    COMMUNITY,
    IMPORTED
}

data class ModInfo(
    val modId: String = System.currentTimeMillis().toString(),
    val name: String = "",
    val version: String = "1.0.0",
    val versionCode: Int = 1,
    val author: String = "",
    val description: String = "",
    val category: ModCategory = ModCategory.PERSONA,
    val source: ModSource = ModSource.LOCAL,
    val installPath: String = "",
    val manifestPath: String = "",
    val enabled: Boolean = true,
    val dependencies: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val rating: Float = 0f,
    val downloadCount: Int = 0,
    val installedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val hasUpdate: Boolean = false,
    val latestVersion: String? = null,
    val readmeContent: String? = null
)
