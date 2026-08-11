package com.lingshu.agent.feature.mod

import android.util.Log
import com.google.gson.Gson
import com.lingshu.agent.core.database.Converters
import com.lingshu.agent.core.database.dao.ModDao
import com.lingshu.agent.core.database.entity.ModCategoryEntity
import com.lingshu.agent.core.database.entity.ModEntity
import com.lingshu.agent.core.model.ModCategory as CoreModCategory
import com.lingshu.agent.core.model.ModInfo
import com.lingshu.agent.core.model.ModSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mod数据仓库
 *
 * 职责：
 * 1. 通过Room数据库持久化Mod元数据（启用状态、安装路径等）
 * 2. 提供Mod信息的响应式数据流（Flow）
 * 3. 核心Mod信息CRUD操作
 * 4. Mod启用状态持久化与查询
 *
 * 注意：
 * - 本地core模块中已有ModCategory枚举与feature/mod中的定义略有区别，
 *   本仓库负责在两者之间做映射转换
 */
@Singleton
class ModRepository @Inject constructor(
    private val modDao: ModDao,
    private val gson: Gson
) {

    companion object {
        private const val TAG = "ModRepository"
    }

    // ==================== 类型转换器 ====================

    private val converters = Converters()

    /**
     * 将feature层的ModCategory映射为core层的ModCategory
     * core层缺少PACK，使用DATA替代（保持兼容性）
     */
    private fun ModCategory.toCore(): CoreModCategory {
        return when (this) {
            ModCategory.PERSONA -> CoreModCategory.PERSONA
            ModCategory.SKILL -> CoreModCategory.SKILL
            ModCategory.THEME -> CoreModCategory.THEME
            ModCategory.AUTOMATION -> CoreModCategory.AUTOMATION
            ModCategory.PACK -> CoreModCategory.DATA
        }
    }

    /**
     * 将core层的ModCategory映射为feature层的ModCategory
     * DATA视为PACK整合包
     */
    private fun CoreModCategory.toFeature(): ModCategory {
        return when (this) {
            CoreModCategory.PERSONA -> ModCategory.PERSONA
            CoreModCategory.SKILL -> ModCategory.SKILL
            CoreModCategory.THEME -> ModCategory.THEME
            CoreModCategory.AUTOMATION -> ModCategory.AUTOMATION
            CoreModCategory.DATA -> ModCategory.PACK
        }
    }

    /**
     * ModEntity -> ModManifest
     * 从持久化数据重建Mod清单对象
     */
    private fun ModEntity.toManifest(): ModManifest {
        // 从 manifestJson 解析出 dependencies 和 tags
        val jsonObj = runCatching { gson.fromJson(manifestJson, Map::class.java) as? Map<*, *> }.getOrNull()
        val depList: List<String> = (jsonObj?.get("dependencies") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        val tagList: List<String> = (jsonObj?.get("tags") as? List<*>)?.filterIsInstance<String>() ?: emptyList()

        return ModManifest(
            id = id,
            name = name,
            version = version,
            versionCode = versionCode,
            author = author,
            description = description,
            category = when (category) {
                ModCategoryEntity.PERSONA -> ModCategory.PERSONA
                ModCategoryEntity.SKILL -> ModCategory.SKILL
                ModCategoryEntity.THEME -> ModCategory.THEME
                ModCategoryEntity.AUTOMATION -> ModCategory.AUTOMATION
                ModCategoryEntity.DATA -> ModCategory.PACK
            },
            minAppVersion = "1.0.0",
            dependencies = depList,
            tags = tagList,
            packagedAt = installedAt
        )
    }

    /**
     * ModEntity -> ModInfo
     */
    private fun ModEntity.toInfo(): ModInfo {
        val jsonObj = runCatching { gson.fromJson(manifestJson, Map::class.java) as? Map<*, *> }.getOrNull()
        val depList: List<String> = (jsonObj?.get("dependencies") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        val tagList: List<String> = (jsonObj?.get("tags") as? List<*>)?.filterIsInstance<String>() ?: emptyList()

        return ModInfo(
            modId = id,
            name = name,
            version = version,
            versionCode = versionCode,
            author = author,
            description = description,
            category = CoreModCategory.SKILL, // Default, will be overwritten as needed
            source = ModSource.LOCAL,
            installPath = installPath,
            manifestPath = "",
            enabled = isEnabled,
            dependencies = depList,
            tags = tagList,
            rating = 0f,
            downloadCount = 0,
            installedAt = installedAt,
            updatedAt = updatedAt,
            hasUpdate = isUpdateAvailable,
            latestVersion = null,
            readmeContent = null
        )
    }

    // ==================== 写入操作 ====================

    /**
     * 保存Mod信息到数据库（安装Mod时调用）
     * 若已存在相同ID则覆盖
     */
    suspend fun saveMod(
        manifest: ModManifest,
        installPath: String,
        manifestPath: String,
        source: ModSource = ModSource.IMPORTED
    ) {
        val now = System.currentTimeMillis()
        val entity = ModEntity(
            id = manifest.id,
            name = manifest.name,
            version = manifest.version,
            versionCode = manifest.versionCode,
            author = manifest.author,
            description = manifest.description,
            category = when (manifest.category) {
                ModCategory.PERSONA -> ModCategoryEntity.PERSONA
                ModCategory.SKILL -> ModCategoryEntity.SKILL
                ModCategory.THEME -> ModCategoryEntity.THEME
                ModCategory.AUTOMATION -> ModCategoryEntity.AUTOMATION
                ModCategory.PACK -> ModCategoryEntity.DATA
            },
            installPath = installPath,
            manifestJson = gson.toJson(manifest),
            isEnabled = true,
            installedAt = now,
            updatedAt = now,
            isUpdateAvailable = false,
            installedSize = 0L
        )
        modDao.upsert(entity)
        Log.d(TAG, "Mod已保存到数据库: ${manifest.id}")
    }

    /**
     * 更新Mod的启用/禁用状态
     */
    suspend fun setModEnabled(modId: String, enabled: Boolean) {
        modDao.setEnabled(modId, enabled)
        Log.d(TAG, "Mod[$modId] 启用状态已更新: enabled=$enabled")
    }

    /**
     * 从数据库删除Mod记录（卸载Mod时调用）
     */
    suspend fun deleteMod(modId: String) {
        modDao.delete(modId)
        Log.d(TAG, "Mod已从数据库删除: $modId")
    }

    /**
     * 设置Mod有可用更新
     */
    suspend fun setModUpdateAvailable(modId: String, latestVersion: String) {
        modDao.setUpdateAvailable(modId, true, System.currentTimeMillis())
    }

    /**
     * 清除Mod更新标记
     */
    suspend fun clearModUpdateFlag(modId: String) {
        modDao.setUpdateAvailable(modId, false, System.currentTimeMillis())
    }

    // ==================== 查询操作 ====================

    /**
     * 观察所有Mod列表（响应式Flow）
     */
    fun observeAllMods(): Flow<List<ModInfo>> {
        return modDao.observeAll().map { entities ->
            entities.map { it.toInfo() }
        }
    }

    /**
     * 观察已启用的Mod列表
     */
    fun observeEnabledMods(): Flow<List<ModInfo>> {
        return modDao.observeAll().map { entities ->
            entities.filter { it.isEnabled }.map { it.toInfo() }
        }
    }

    /**
     * 根据分类观察Mod列表
     */
    fun observeModsByCategory(category: ModCategory): Flow<List<ModInfo>> {
        val targetEntityCategory = when (category) {
            ModCategory.PERSONA -> ModCategoryEntity.PERSONA
            ModCategory.SKILL -> ModCategoryEntity.SKILL
            ModCategory.THEME -> ModCategoryEntity.THEME
            ModCategory.AUTOMATION -> ModCategoryEntity.AUTOMATION
            ModCategory.PACK -> ModCategoryEntity.DATA
        }
        return modDao.observeAll().map { entities ->
            entities.filter { it.category == targetEntityCategory }.map { it.toInfo() }
        }
    }

    /**
     * 搜索Mod（名称、作者、描述匹配）
     */
    fun searchMods(keyword: String): Flow<List<ModInfo>> {
        return modDao.observeAll().map { entities ->
            entities.filter {
                it.name.contains(keyword, ignoreCase = true) ||
                it.author.contains(keyword, ignoreCase = true) ||
                it.description.contains(keyword, ignoreCase = true)
            }.map { it.toInfo() }
        }
    }

    /**
     * 根据ID获取Mod（一次性查询）
     */
    suspend fun getModById(modId: String): ModInfo? {
        return modDao.getById(modId)?.toInfo()
    }

    /**
     * 获取Mod的启用状态
     * @return 启用状态；数据库无记录时返回null（让调用方决定默认值）
     */
    suspend fun getModEnabledState(modId: String): Boolean? {
        val entity = modDao.getById(modId) ?: return null
        return entity.isEnabled
    }

    /**
     * 获取所有启用的Mod（一次性查询）
     */
    suspend fun getEnabledModsOnce(): List<ModInfo> {
        return modDao.observeAll().first().filter { it.isEnabled }.map { it.toInfo() }
    }

    /**
     * 获取依赖某Mod的所有Mod列表（用于卸载时提示影响）
     */
    fun observeDependents(modId: String): Flow<List<ModInfo>> {
        // DAO doesn't support dependency tracking natively; filter from manifestJson
        return modDao.observeAll().map { entities ->
            entities.filter { entity ->
                val jsonObj = runCatching { gson.fromJson(entity.manifestJson, Map::class.java) as? Map<*, *> }.getOrNull()
                val deps = jsonObj?.get("dependencies") as? List<*> ?: emptyList<Any>()
                modId in deps.mapNotNull { it as? String }
            }.map { it.toInfo() }
        }
    }

    /**
     * 根据ModManifest列表重新持久化（启动时校准数据库用）
     */
    suspend fun syncWithLoadedMods(manifests: List<Pair<ModManifest, Pair<String, String>>>) {
        val now = System.currentTimeMillis()
        for ((manifest, paths) in manifests) {
            val (installPath, _) = paths
            val entity = ModEntity(
                id = manifest.id,
                name = manifest.name,
                version = manifest.version,
                versionCode = manifest.versionCode,
                author = manifest.author,
                description = manifest.description,
                category = when (manifest.category) {
                    ModCategory.PERSONA -> ModCategoryEntity.PERSONA
                    ModCategory.SKILL -> ModCategoryEntity.SKILL
                    ModCategory.THEME -> ModCategoryEntity.THEME
                    ModCategory.AUTOMATION -> ModCategoryEntity.AUTOMATION
                    ModCategory.PACK -> ModCategoryEntity.DATA
                },
                installPath = installPath,
                manifestJson = gson.toJson(manifest),
                isEnabled = getModEnabledState(manifest.id) ?: true,
                installedAt = now,
                updatedAt = now,
                isUpdateAvailable = false,
                installedSize = 0L
            )
            modDao.upsert(entity)
        }
        Log.d(TAG, "Mod数据库校准完成，共写入 ${manifests.size} 条记录")
    }
}
