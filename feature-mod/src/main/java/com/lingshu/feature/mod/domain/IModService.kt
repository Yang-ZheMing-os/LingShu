package com.lingshu.feature.mod.domain

import com.lingshu.core.common.error.Result
import java.io.File

/** 所有已启用 Mod 的声明式内容聚合结果 */
data class AggregatedDeclarativeContent(
    val quickActions: List<ModQuickAction>,
    val homeNavCards: List<ModHomeNav>,
    val aliases: List<ModAlias>,
    val promptSnippets: List<ModPromptSnippet>,
    val personaPrompts: List<String>
) {
    companion object {
        val EMPTY = AggregatedDeclarativeContent(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    }
}

interface IModService {
    suspend fun installMod(file: File): Result<Mod>
    suspend fun enableMod(modId: String): Result<Unit>
    suspend fun disableMod(modId: String): Result<Unit>
    suspend fun uninstallMod(modId: String): Result<Unit>
    fun listMods(): List<Mod>
    suspend fun fetchModList(): Result<List<ModInfo>>
    suspend fun downloadMod(modId: String): Result<File>

    // ==================== 声明式能力 ====================
    /** 获取所有已启用 Mod 的声明式内容（用于聊天页 Chip / 别名匹配 / RAG 片段 / 人格附加 prompt） */
    fun getDeclarativeContent(): AggregatedDeclarativeContent
    /** 输入一句用户话，找是否有匹配的别名（命中则返回 canonicalCommand 可直接替换用户输入发送） */
    fun resolveAlias(userInput: String): ModAlias?
}
