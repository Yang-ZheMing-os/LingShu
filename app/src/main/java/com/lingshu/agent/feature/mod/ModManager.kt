package com.lingshu.agent.feature.mod

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.lingshu.agent.feature.control.ScriptEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mod分类枚举
 *
 * PERSONA:    人格扩展（包含预设人格、角色设定等）
 * SKILL:      技能脚本（JavaScript脚本，扩展AI能力）
 * THEME:      UI主题（颜色、字体、样式包）
 * AUTOMATION: 自动化流程（定时任务、触发规则、工作流）
 * PACK:       整合包（包含以上多种类型的Mod包）
 */
enum class ModCategory(val displayName: String) {
    @SerializedName("persona") PERSONA("人格包"),
    @SerializedName("skill") SKILL("技能包"),
    @SerializedName("theme") THEME("主题包"),
    @SerializedName("automation") AUTOMATION("自动化"),
    @SerializedName("pack") PACK("整合包")
}

/**
 * Mod权限等级
 *
 * NORMAL:   普通权限（纯UI展示、数据读取），自动授权
 * MEDIUM:   中级权限（调用非敏感系统API），安装时提示
 * HIGH:     高级权限（调用敏感API如通知、闹钟），安装时警告
 * DANGEROUS: 危险权限（无障碍操作、悬浮窗控制），需用户二次确认
 */
enum class PermissionLevel(val displayName: String) {
    @SerializedName("normal") NORMAL("普通"),
    @SerializedName("medium") MEDIUM("中级"),
    @SerializedName("high") HIGH("高级"),
    @SerializedName("dangerous") DANGEROUS("危险")
}

/**
 * 权限声明
 */
data class PermissionDeclaration(
    val level: PermissionLevel,
    val reason: String
)

/**
 * Mod清单文件（manifest.json）数据结构
 *
 * 对应.lspack压缩包内根目录的 manifest.json 文件
 */
data class ModManifest(
    /** 唯一ID（建议反向域名格式，如 com.example.mypersona） */
    val id: String,
    /** Mod显示名称 */
    val name: String,
    /** 语义化版本号，如 "1.2.3" */
    val version: String,
    /** 版本号整数（用于比较大小） */
    val versionCode: Int = 1,
    /** 作者名称 */
    val author: String = "",
    /** 详细描述 */
    val description: String = "",
    /** Mod分类 */
    val category: ModCategory = ModCategory.PACK,
    /** 最低支持的App版本（如 "1.0.0"） */
    val minAppVersion: String = "1.0.0",
    /** 依赖的其他Mod ID列表 */
    val dependencies: List<String> = emptyList(),
    /** 标签（用于搜索分类） */
    val tags: List<String> = emptyList(),
    /** Mod图标文件在包内的路径 */
    val icon: String? = null,
    /** 预览截图文件列表（包内路径） */
    val screenshots: List<String> = emptyList(),
    /** 更新日志 */
    val changelog: String = "",
    /** 许可证 */
    val license: String? = null,
    /** 主页/仓库链接 */
    val homepage: String? = null,
    /** 脚本入口文件（SKILL类型） */
    val entryScript: String? = null,
    /** 主题配置文件路径（THEME类型） */
    val themeConfig: String? = null,
    /** 自动化流程配置文件（AUTOMATION类型） */
    val automationConfig: String? = null,
    /** 人格数据文件列表（PERSONA类型，包内路径） */
    val personaFiles: List<String> = emptyList(),
    /** 签名校验（SHA-256，可选） */
    val signature: String? = null,
    /** Mod打包时间戳 */
    val packagedAt: Long = 0L,
    /** 权限等级声明 */
    val permissionLevel: PermissionLevel = PermissionLevel.NORMAL,
    /** 各权限声明详情（key为权限标识，value为理由） */
    val permissions: Map<String, String> = emptyMap()
) {
    companion object {
        /** 清单文件名 */
        const val MANIFEST_FILE_NAME = "manifest.json"

        /**
         * 从JSON字符串解析ModManifest
         */
        fun fromJson(json: String, gson: Gson = Gson()): ModManifest {
            return gson.fromJson(json, ModManifest::class.java)
        }

        /**
         * 序列化为格式化JSON字符串
         */
        fun toJson(manifest: ModManifest, gson: Gson = Gson()): String {
            return gson.toJson(manifest)
        }
    }
}

/**
 * 已加载的Mod运行时实例
 */
data class LoadedMod(
    val manifest: ModManifest,
    val installDir: File,
    val manifestFile: File,
    var enabled: Boolean = true,
    var loadedAt: Long = System.currentTimeMillis(),
    var scriptInstance: Any? = null,
    var themeConfig: Map<String, Any>? = null,
    var automationConfigs: List<Any> = emptyList(),
    var personaData: List<Any> = emptyList()
) {
    val modId: String get() = manifest.id
    val name: String get() = manifest.name
    val category: ModCategory get() = manifest.category
}

/**
 * Mod加载与管理器
 *
 * 职责：
 * 1. .lspack格式（ZIP压缩包）的解压和校验
 * 2. manifest.json解析（名称、版本、作者、分类等）
 * 3. 动态加载技能脚本(JavaScript)、UI主题、自动化流程、人格包
 * 4. 启用/禁用/卸载Mod（无需重启App）
 * 5. 版本更新检测
 * 6. 依赖关系检查和加载顺序处理
 */
@Singleton
class ModManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gson: Gson,
    private val modRepository: ModRepository,
    private val scriptEngine: ScriptEngine
) {

    companion object {
        private const val TAG = "ModManager"

        /** .lspack 文件扩展名 */
        const val LS_PACK_EXTENSION = ".lspack"

        /** Mod安装根目录 */
        private const val MODS_DIR_NAME = "mods"

        /** 临时解压目录名 */
        private const val TEMP_EXTRACT_DIR = "temp_extract"

        /** manifest.json 中校验字段所需的文件列表 */
        private val REQUIRED_MANIFEST_FIELDS = listOf("id", "name", "version")

        /** App版本号（用于 minAppVersion 校验，后续可从 PackageManager 动态读取） */
        private const val CURRENT_APP_VERSION = "1.0.0"

        /** 版本号比较的正则（提取数字段） */
        private val VERSION_DIGIT_REGEX = Regex("""\d+""")
    }

    // ==================== 目录初始化 ====================

    /** Mod安装目录 */
    private val modsDir: File by lazy {
        File(context.filesDir, MODS_DIR_NAME).apply {
            if (!exists()) mkdirs()
        }
    }

    /** 临时解压目录 */
    private val tempExtractDir: File by lazy {
        File(context.cacheDir, TEMP_EXTRACT_DIR).apply {
            if (!exists()) mkdirs()
        }
    }

    // ==================== 加载状态 ====================

    /** 已加载的Mod集合（ID -> LoadedMod） */
    private val loadedMods = LinkedHashMap<String, LoadedMod>()

    /** 加载状态流 */
    private val _loadState = MutableStateFlow<ModLoadState>(ModLoadState.Idle)
    val loadState: StateFlow<ModLoadState> = _loadState.asStateFlow()

    /** 已启用的Mod ID集合（内存快照，便于快速查询） */
    private val enabledModIds = LinkedHashSet<String>()

    // ==================== 初始化与加载 ====================

    init {
        // 启动时扫描已安装的Mod
        scanInstalledMods()
    }

    /**
     * 扫描并加载已安装的所有Mod
     * 通常在App启动时调用
     */
    fun scanInstalledMods() {
        _loadState.value = ModLoadState.Scanning
        Log.d(TAG, "开始扫描已安装Mod，目录: ${modsDir.absolutePath}")

        var loadedCount = 0
        var failedCount = 0

        val modDirs = modsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()

        for (modDir in modDirs) {
            try {
                val manifestFile = File(modDir, ModManifest.MANIFEST_FILE_NAME)
                if (!manifestFile.exists()) {
                    Log.w(TAG, "跳过目录 ${modDir.name}：找不到 manifest.json")
                    continue
                }

                val manifestJson = manifestFile.readText(Charsets.UTF_8)
                val manifest = ModManifest.fromJson(manifestJson, gson)

                // 验证必需字段
                if (!validateManifestFields(manifest)) {
                    Log.w(TAG, "跳过Mod ${modDir.name}：manifest 缺少必需字段")
                    failedCount++
                    continue
                }

                // 加载Mod（不执行脚本，仅元数据加载）
                val loaded = loadModFromDirectory(manifest, modDir, manifestFile)
                loadedMods[manifest.id] = loaded
                if (loaded.enabled) {
                    enabledModIds.add(manifest.id)
                }
                loadedCount++
                Log.d(TAG, "已加载Mod: ${manifest.name} v${manifest.version} (${manifest.id})")

            } catch (e: Exception) {
                Log.e(TAG, "加载Mod失败 ${modDir.name}: ${e.message}", e)
                failedCount++
            }
        }

        val finalLoaded = loadedCount
        val finalFailed = failedCount
        _loadState.value = ModLoadState.Ready(
            loadedCount = finalLoaded,
            failedCount = finalFailed
        )
        Log.i(TAG, "Mod扫描完成：成功 $finalLoaded 个，失败 $finalFailed 个")
    }

    /**
     * 从已安装目录加载一个Mod的元数据
     */
    private fun loadModFromDirectory(
        manifest: ModManifest,
        dir: File,
        manifestFile: File
    ): LoadedMod {
        // 从数据库获取启用状态（用户偏好）
        val persistedEnabled = runBlocking { modRepository.getModEnabledState(manifest.id) }
        val enabled = persistedEnabled ?: true  // 默认启用

        val loaded = LoadedMod(
            manifest = manifest,
            installDir = dir,
            manifestFile = manifestFile,
            enabled = enabled
        )

        // 如果是启用状态，进行资源加载
        if (enabled) {
            loadModResources(loaded)
        }

        return loaded
    }

    /**
     * 加载Mod的资源（脚本、主题、自动化配置等）
     * @return 是否加载成功
     */
    private fun loadModResources(loaded: LoadedMod): Boolean {
        val manifest = loaded.manifest
        var allOk = true

        // 1. 加载技能脚本
        if (manifest.category == ModCategory.SKILL || manifest.category == ModCategory.PACK) {
            manifest.entryScript?.let { scriptPath ->
                val scriptFile = File(loaded.installDir, scriptPath)
                if (scriptFile.exists()) {
                    try {
                        val scriptContent = scriptFile.readText(Charsets.UTF_8)
                        runBlocking { scriptEngine.loadScript(scriptContent) }
                        Log.d(TAG, "Mod[${manifest.id}] 脚本加载完成: $scriptPath")
                    } catch (e: Exception) {
                        Log.e(TAG, "Mod[${manifest.id}] 脚本加载失败: ${e.message}", e)
                        allOk = false
                    }
                } else {
                    Log.w(TAG, "Mod[${manifest.id}] 脚本文件不存在: $scriptPath")
                }
            }
        }

        // 2. 加载主题配置
        if (manifest.category == ModCategory.THEME || manifest.category == ModCategory.PACK) {
            manifest.themeConfig?.let { configPath ->
                val configFile = File(loaded.installDir, configPath)
                if (configFile.exists()) {
                    try {
                        val jsonContent = configFile.readText(Charsets.UTF_8)
                        val theme = parseThemeConfig(jsonContent)
                        loaded.themeConfig = theme
                        Log.d(TAG, "Mod[${manifest.id}] 主题配置加载完成")
                    } catch (e: Exception) {
                        Log.e(TAG, "Mod[${manifest.id}] 主题配置加载失败: ${e.message}", e)
                        allOk = false
                    }
                }
            }
        }

        // 3. 加载自动化流程配置
        if (manifest.category == ModCategory.AUTOMATION || manifest.category == ModCategory.PACK) {
            manifest.automationConfig?.let { configPath ->
                val configFile = File(loaded.installDir, configPath)
                if (configFile.exists()) {
                    try {
                        val jsonContent = configFile.readText(Charsets.UTF_8)
                        val automations = parseAutomationConfigs(jsonContent)
                        loaded.automationConfigs = automations
                        Log.d(TAG, "Mod[${manifest.id}] 自动化流程加载完成，共 ${automations.size} 条")
                    } catch (e: Exception) {
                        Log.e(TAG, "Mod[${manifest.id}] 自动化配置加载失败: ${e.message}", e)
                        allOk = false
                    }
                }
            }
        }

        // 4. 加载人格文件
        if (manifest.category == ModCategory.PERSONA || manifest.category == ModCategory.PACK) {
            if (manifest.personaFiles.isNotEmpty()) {
                val personas = mutableListOf<Any>()
                for (personaPath in manifest.personaFiles) {
                    val personaFile = File(loaded.installDir, personaPath)
                    if (personaFile.exists()) {
                        try {
                            val personaJson = personaFile.readText(Charsets.UTF_8)
                            personas.add(personaJson)
                        } catch (e: Exception) {
                            Log.w(TAG, "Mod[${manifest.id}] 人格文件加载失败: $personaPath, ${e.message}")
                        }
                    }
                }
                loaded.personaData = personas
                Log.d(TAG, "Mod[${manifest.id}] 人格数据加载完成，共 ${personas.size} 份")
            }
        }

        return allOk
    }

    // ==================== .lspack 安装 ====================

    /**
     * 安装一个 .lspack 格式的Mod包
     *
     * 流程：
     * 1. 校验ZIP格式
     * 2. 读取并校验 manifest.json（必需字段 + 签名可选）
     * 3. 检查依赖是否已安装
     * 4. 解压到正式安装目录
     * 5. 注册到已加载列表
     * 6. 持久化到数据库
     *
     * @param lspackFile .lspack 文件
     * @return 安装结果（成功返回新安装的 LoadedMod，失败则抛出异常信息）
     */
    suspend fun installMod(lspackFile: File): ModInstallResult {
        _loadState.value = ModLoadState.Installing(lspackFile.name)
        Log.d(TAG, "开始安装Mod包: ${lspackFile.absolutePath}")

        val extractTarget = File(tempExtractDir, "install_${System.currentTimeMillis()}")
        try {
            // 步骤1：校验ZIP格式并解压到临时目录
            if (!extractZip(lspackFile, extractTarget)) {
                return ModInstallResult.Failed("解压失败：文件格式不正确或已损坏")
            }

            // 步骤2：读取并校验 manifest.json
            val manifestFile = File(extractTarget, ModManifest.MANIFEST_FILE_NAME)
            if (!manifestFile.exists()) {
                return ModInstallResult.Failed("Mod包缺少 manifest.json 文件")
            }

            val manifest: ModManifest
            try {
                val manifestJson = manifestFile.readText(Charsets.UTF_8)
                manifest = ModManifest.fromJson(manifestJson, gson)
            } catch (e: Exception) {
                return ModInstallResult.Failed("manifest.json 解析失败: ${e.message}")
            }

            if (!validateManifestFields(manifest)) {
                return ModInstallResult.Failed(
                    "manifest.json 缺少必需字段，需要: ${REQUIRED_MANIFEST_FIELDS.joinToString()}"
                )
            }

            // 步骤3：检查App版本兼容
            if (!isAppVersionCompatible(manifest.minAppVersion)) {
                return ModInstallResult.Failed(
                    "Mod需要App版本 ≥ ${manifest.minAppVersion}，当前版本为 $CURRENT_APP_VERSION"
                )
            }

            // 步骤3.5：恶意代码安全扫描
            val scanResult = scanModPackageForMaliciousCode(extractTarget, manifest)
            if (scanResult.hasSevereIssues) {
                val detail = scanResult.findings
                    .filter { it.severity == "严重" }
                    .joinToString("\n") { "  行${it.line} (${it.file}): ${it.description}" }
                return ModInstallResult.SecurityBlocked(
                    manifest.name,
                    "检测到高危代码特征，安装已阻止：\n$detail"
                )
            }
            if (!scanResult.isClean) {
                Log.w(TAG, "Mod [${manifest.id}] 检测到 ${scanResult.findings.size} 个中低危代码特征（放行但记录）")
                for (f in scanResult.findings) {
                    Log.w(TAG, "  [${f.severity}] ${f.file}:${f.line} — ${f.description}")
                }
            }

            // 步骤3.6：签名校验（如果 manifest 中包含签名）
            manifest.signature?.let { expectedSig ->
                val manifestFileInPackage = File(extractTarget, ModManifest.MANIFEST_FILE_NAME)
                val actualSig = sha256(manifestFileInPackage)
                if (!actualSig.equals(expectedSig, ignoreCase = true)) {
                    return ModInstallResult.Failed(
                        "签名校验失败：Mod包可能被篡改。期望签名: ${expectedSig.take(16)}...，实际: ${actualSig.take(16)}..."
                    )
                }
                Log.d(TAG, "Mod [${manifest.id}] 签名校验通过")
            }

            // 步骤4：检查依赖（仅警告，不阻止安装）
            val missingDeps = mutableListOf<String>()
            for (depId in manifest.dependencies) {
                if (!loadedMods.containsKey(depId)) {
                    missingDeps.add(depId)
                }
            }

            // 步骤5：如果已存在，先备份旧版本
            val finalDir = File(modsDir, manifest.id)
            val backupDir = File(modsDir, "${manifest.id}_backup_${System.currentTimeMillis()}")
            if (finalDir.exists()) {
                val existing = loadedMods[manifest.id]
                if (existing != null && !isVersionNewer(manifest.version, existing.manifest.version)) {
                    return ModInstallResult.Failed(
                        "已安装同名Mod v${existing.manifest.version}，新版本号不高于旧版本"
                    )
                }
                // 卸载旧版资源（不移除，等下直接覆盖）
                existing?.let { unloadModResources(it) }
                finalDir.copyRecursively(backupDir, overwrite = true)
                finalDir.deleteRecursively()
            }

            // 步骤6：将临时目录移动到正式安装目录
            extractTarget.copyRecursively(finalDir, overwrite = true)
            val finalManifestFile = File(finalDir, ModManifest.MANIFEST_FILE_NAME)

            // 步骤7：注册到运行时
            val loaded = LoadedMod(
                manifest = manifest,
                installDir = finalDir,
                manifestFile = finalManifestFile,
                enabled = true
            )
            loadModResources(loaded)
            loadedMods[manifest.id] = loaded
            enabledModIds.add(manifest.id)

            // 步骤8：持久化到数据库
            modRepository.saveMod(manifest, finalDir.absolutePath, finalManifestFile.absolutePath)

            // 清理临时目录和备份
            extractTarget.deleteRecursively()
            backupDir.deleteRecursively()

            Log.i(TAG, "Mod安装成功: ${manifest.name} v${manifest.version}")
            _loadState.value = ModLoadState.Ready(loadedMods.size, 0)
            return ModInstallResult.Success(loaded, missingDeps)

        } catch (e: Exception) {
            Log.e(TAG, "安装Mod失败: ${e.message}", e)
            extractTarget.deleteRecursively()
            _loadState.value = ModLoadState.Error(e.message ?: "未知错误")
            return ModInstallResult.Failed("安装异常: ${e.message}")
        }
    }

    // ==================== 启用/禁用/卸载 ====================

    /**
     * 启用一个已安装的Mod
     * @return 是否成功启用
     */
    suspend fun enableMod(modId: String): Boolean {
        val loaded = loadedMods[modId] ?: run {
            Log.w(TAG, "启用失败：Mod未加载 $modId")
            return false
        }

        if (loaded.enabled) {
            Log.d(TAG, "Mod已处于启用状态: $modId")
            return true
        }

        return try {
            // 加载资源
            loadModResources(loaded)
            loaded.enabled = true
            enabledModIds.add(modId)

            // 持久化
            modRepository.setModEnabled(modId, true)

            Log.i(TAG, "Mod已启用: ${loaded.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "启用Mod失败 $modId: ${e.message}", e)
            false
        }
    }

    /**
     * 禁用一个已启用的Mod（无需重启）
     * @return 是否成功禁用
     */
    suspend fun disableMod(modId: String): Boolean {
        val loaded = loadedMods[modId] ?: run {
            Log.w(TAG, "禁用失败：Mod未加载 $modId")
            return false
        }

        if (!loaded.enabled) {
            Log.d(TAG, "Mod已处于禁用状态: $modId")
            return true
        }

        return try {
            // 卸载资源（脚本清理等）
            unloadModResources(loaded)
            loaded.enabled = false
            enabledModIds.remove(modId)

            // 持久化
            modRepository.setModEnabled(modId, false)

            Log.i(TAG, "Mod已禁用: ${loaded.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "禁用Mod失败 $modId: ${e.message}", e)
            false
        }
    }

    /**
     * 卸载一个Mod（从磁盘删除）
     * @return 是否成功卸载
     */
    suspend fun uninstallMod(modId: String): Boolean {
        val loaded = loadedMods[modId] ?: run {
            Log.w(TAG, "卸载失败：Mod不存在 $modId")
            return false
        }

        return try {
            // 先禁用/卸载资源
            unloadModResources(loaded)
            loaded.enabled = false
            enabledModIds.remove(modId)

            // 删除安装目录
            val dirDeleted = loaded.installDir.deleteRecursively()
            if (!dirDeleted) {
                Log.w(TAG, "Mod目录删除不彻底: ${loaded.installDir.absolutePath}")
            }

            // 从运行时移除
            loadedMods.remove(modId)

            // 从数据库移除
            modRepository.deleteMod(modId)

            Log.i(TAG, "Mod已卸载: ${loaded.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "卸载Mod失败 $modId: ${e.message}", e)
            false
        }
    }

    /**
     * 卸载Mod的运行时资源（不删除文件）
     */
    private fun unloadModResources(loaded: LoadedMod) {
        val manifest = loaded.manifest

        // 清理脚本引擎中的脚本（可选，引擎本身可能需要支持卸载）
        if (manifest.entryScript != null) {
            try {
                // scriptEngine.unloadScript(manifest.entryScript) // 若引擎支持
            } catch (_: Exception) {}
        }

        // 清理主题和自动化的运行时状态
        loaded.scriptInstance = null
        loaded.themeConfig = null
        loaded.automationConfigs = emptyList()
        loaded.personaData = emptyList()
    }

    // ==================== 更新检测 ====================

    /**
     * 检测指定Mod是否有可更新版本（通过本地清单比较）
     *
     * @param current 当前已安装的Mod
     * @param newManifestPath 新版本 .lspack 文件路径（可选）
     * @return 检测结果
     */
    fun checkUpdate(current: LoadedMod, newManifestPath: File? = null): ModUpdateResult {
        newManifestPath?.let { file ->
            if (!file.exists()) return ModUpdateResult.NoUpdate
            try {
                val tempExtract = File(tempExtractDir, "check_${current.modId}")
                extractZip(file, tempExtract)
                val manifestFile = File(tempExtract, ModManifest.MANIFEST_FILE_NAME)
                if (manifestFile.exists()) {
                    val json = manifestFile.readText(Charsets.UTF_8)
                    val newManifest = ModManifest.fromJson(json, gson)
                    tempExtract.deleteRecursively()

                    return if (isVersionNewer(newManifest.version, current.manifest.version)) {
                        ModUpdateResult.Available(newManifest, file)
                    } else {
                        ModUpdateResult.NoUpdate
                    }
                }
                tempExtract.deleteRecursively()
            } catch (e: Exception) {
                Log.w(TAG, "检查更新失败: ${e.message}")
            }
        }

        // 无本地文件时，仅返回状态说明
        return ModUpdateResult.Unknown("需要提供新版本 .lspack 文件路径进行本地检测")
    }

    /**
     * 批量检测所有Mod的更新状态
     * 当前仅做占位，实际生产可配合网络API
     */
    suspend fun checkAllUpdates(): Map<String, ModUpdateResult> {
        val results = mutableMapOf<String, ModUpdateResult>()
        for (mod in loadedMods.values) {
            results[mod.modId] = ModUpdateResult.Unknown("未配置远程更新源")
        }
        return results
    }

    // ==================== 查询接口 ====================

    /** 获取所有已加载的Mod列表 */
    fun getAllMods(): List<LoadedMod> = loadedMods.values.toList()

    /** 获取所有已启用的Mod列表 */
    fun getEnabledMods(): List<LoadedMod> = loadedMods.values.filter { it.enabled }

    /** 根据分类获取Mod */
    fun getModsByCategory(category: ModCategory): List<LoadedMod> =
        loadedMods.values.filter { it.category == category }

    /** 根据ID获取单个Mod */
    fun getModById(modId: String): LoadedMod? = loadedMods[modId]

    /** 是否已安装指定ID的Mod */
    fun isModInstalled(modId: String): Boolean = loadedMods.containsKey(modId)

    /** 指定Mod是否已启用 */
    fun isModEnabled(modId: String): Boolean = enabledModIds.contains(modId)

    /** 搜索Mod（按名称、作者、标签） */
    fun searchMods(keyword: String): List<LoadedMod> {
        val kw = keyword.lowercase()
        return loadedMods.values.filter { m ->
            m.name.lowercase().contains(kw) ||
                    m.manifest.author.lowercase().contains(kw) ||
                    m.manifest.tags.any { it.lowercase().contains(kw) } ||
                    m.manifest.description.lowercase().contains(kw)
        }
    }

    // ==================== .lspack 打包（用于开发/导出 ====================

    /**
     * 将一个目录打包为 .lspack 文件
     * （用于Mod开发者或分享已安装的Mod）
     *
     * @param sourceDir 包含 manifest.json 和所有资源的源目录
     * @param outputFile 输出 .lspack 文件
     * @return 是否打包成功
     */
    fun packToLspack(sourceDir: File, outputFile: File): Boolean {
        val manifestFile = File(sourceDir, ModManifest.MANIFEST_FILE_NAME)
        if (!manifestFile.exists()) {
            Log.e(TAG, "打包失败：源目录缺少 manifest.json")
            return false
        }

        return try {
            ZipOutputStream(FileOutputStream(outputFile)).use { zos ->
                sourceDir.walkTopDown()
                    .filter { it.isFile }
                    .forEach { file ->
                        val entryName = file.relativeTo(sourceDir).path
                        zos.putNextEntry(ZipEntry(entryName))
                        file.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
            }
            Log.i(TAG, "Mod打包完成: ${outputFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "打包Mod失败: ${e.message}", e)
            false
        }
    }

    // ==================== 内部校验与工具 ====================

    /**
     * 校验 Manifest 是否包含必需字段
     */
    private fun validateManifestFields(manifest: ModManifest): Boolean {
        if (manifest.id.isBlank()) return false
        if (manifest.name.isBlank()) return false
        if (manifest.version.isBlank()) return false
        return true
    }

    /**
     * App版本是否兼容（版本号比较）
     */
    private fun isAppVersionCompatible(minRequired: String): Boolean {
        return compareVersions(CURRENT_APP_VERSION, minRequired) >= 0
    }

    /**
     * 检查 newVersion 是否严格比 oldVersion 更新
     */
    private fun isVersionNewer(newVersion: String, oldVersion: String): Boolean {
        return compareVersions(newVersion, oldVersion) > 0
    }

    /**
     * 比较两个语义化版本号
     * @return >0: a更新, <0: b更新, =0: 相同
     */
    private fun compareVersions(a: String, b: String): Int {
        val aParts = VERSION_DIGIT_REGEX.findAll(a).map { it.value.toInt() }.toList()
        val bParts = VERSION_DIGIT_REGEX.findAll(b).map { it.value.toInt() }.toList()
        val maxLen = maxOf(aParts.size, bParts.size)
        for (i in 0 until maxLen) {
            val ai = aParts.getOrNull(i) ?: 0
            val bi = bParts.getOrNull(i) ?: 0
            if (ai != bi) return ai - bi
        }
        return 0
    }

    /**
     * 解压ZIP（.lspack）到目标目录
     */
    private fun extractZip(zipFile: File, targetDir: File): Boolean {
        return try {
            if (targetDir.exists()) targetDir.deleteRecursively()
            targetDir.mkdirs()

            ZipInputStream(FileInputStream(zipFile)).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val outFile = File(targetDir, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            zis.copyTo(fos)
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "解压ZIP失败: ${e.message}", e)
            targetDir.deleteRecursively()
            false
        }
    }

    /**
     * 计算文件的SHA-256（用于签名校验）
     */
    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead = fis.read(buffer)
            while (bytesRead != -1) {
                md.update(buffer, 0, bytesRead)
                bytesRead = fis.read(buffer)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ==================== 恶意代码检测 ====================

    /**
     * 扫描脚本文件中的恶意代码特征
     *
     * 检测规则（Android沙箱安全策略）：
     * 1. 禁止 eval() 动态执行 —— 防止代码注入
     * 2. 禁止 Function() 构造函数 —— 等同于 eval
     * 3. 禁止 setTimeout/setInterval 字符串形式 —— 变体 eval
     * 4. 禁止访问 java.lang.Runtime / ProcessBuilder —— 防止提权
     * 5. 禁止文件系统访问（java.io / java.nio） —— 沙箱隔离
     * 6. 禁止网络访问（Socket / HttpURLConnection / OkHttp） —— 沙箱隔离
     *
     * @return 恶意代码检测结果（包含触发规则的行号和描述）
     */
    private fun scanForMaliciousCode(scriptContent: String, scriptFileName: String): MaliciousCodeScanResult {
        val findings = mutableListOf<MaliciousCodeFinding>()
        val lines = scriptContent.lines()

        // 规则模式：正则 → 风险描述
        data class ScanRule(val regex: Regex, val description: String, val severity: String)
        val rules = listOf(
            ScanRule(Regex("""\beval\s*\("""), "禁止使用 eval() 动态执行代码", "严重"),
            ScanRule(Regex("""\bnew\s+Function\s*\("""), "禁止使用 Function() 构造函数", "严重"),
            ScanRule(Regex("""\bsetTimeout\s*\(\s*["']"""), "禁止 setTimeout 字符串形式调用", "严重"),
            ScanRule(Regex("""\bsetInterval\s*\(\s*["']"""), "禁止 setInterval 字符串形式调用", "严重"),
            ScanRule(Regex("""java\.lang\.Runtime"""), "禁止访问 Java Runtime 类（提权风险）", "严重"),
            ScanRule(Regex("""java\.lang\.ProcessBuilder"""), "禁止访问 ProcessBuilder（提权风险）", "严重"),
            ScanRule(Regex("""java\.io\.\w+"""), "禁止访问 java.io 包（文件系统沙箱）", "严重"),
            ScanRule(Regex("""java\.nio\.\w+"""), "禁止访问 java.nio 包（文件系统沙箱）", "严重"),
            ScanRule(Regex("""java\.net\.Socket"""), "禁止访问网络 Socket（网络沙箱）", "严重"),
            ScanRule(Regex("""java\.net\.HttpURLConnection"""), "禁止访问 HttpURLConnection（网络沙箱）", "严重"),
            ScanRule(Regex("""okhttp3\."""), "禁止访问 OkHttp 库（网络沙箱）", "严重"),
            ScanRule(Regex("""\.exec\s*\("""), "禁止调用 exec() 执行系统命令", "严重"),
            ScanRule(Regex("""__proto__"""), "禁止原型链污染攻击", "中等"),
            ScanRule(Regex("""constructor\s*\(\s*\)\s*\{"""), "禁止 constructor 篡改", "中等")
        )

        for ((lineIdx, line) in lines.withIndex()) {
            for (rule in rules) {
                if (rule.regex.containsMatchIn(line)) {
                    findings.add(
                        MaliciousCodeFinding(
                            line = lineIdx + 1,
                            file = scriptFileName,
                            description = rule.description,
                            severity = rule.severity,
                            snippet = line.trim().take(80)
                        )
                    )
                }
            }
        }

        val isClean = findings.isEmpty()
        val hasSevere = findings.any { it.severity == "严重" }

        return MaliciousCodeScanResult(
            isClean = isClean,
            hasSevereIssues = hasSevere,
            findings = findings
        )
    }

    /**
     * 对Mod包内所有脚本文件执行安全扫描
     */
    private fun scanModPackageForMaliciousCode(extractDir: File, manifest: ModManifest): MaliciousCodeScanResult {
        val allFindings = mutableListOf<MaliciousCodeFinding>()
        val scriptExtensions = setOf("js", "mjs")

        // 1. 扫描 entryScript
        manifest.entryScript?.let { scriptPath ->
            val scriptFile = File(extractDir, scriptPath)
            if (scriptFile.exists() && scriptFile.extension in scriptExtensions) {
                val content = scriptFile.readText(Charsets.UTF_8)
                val result = scanForMaliciousCode(content, scriptPath)
                allFindings.addAll(result.findings)
            }
        }

        // 2. 扫描 automation/flows.json 内嵌脚本
        manifest.automationConfig?.let { configPath ->
            val configFile = File(extractDir, configPath)
            if (configFile.exists()) {
                val content = configFile.readText(Charsets.UTF_8)
                val result = scanForMaliciousCode(content, configPath)
                allFindings.addAll(result.findings)
            }
        }

        // 3. 扫描所有 .js / .mjs 文件
        extractDir.walkTopDown()
            .filter { it.isFile && it.extension in scriptExtensions }
            .forEach { jsFile ->
                val relativePath = jsFile.relativeTo(extractDir).path
                // 跳过已扫描过的
                if (relativePath != manifest.entryScript && relativePath != manifest.automationConfig) {
                    val content = jsFile.readText(Charsets.UTF_8)
                    val result = scanForMaliciousCode(content, relativePath)
                    allFindings.addAll(result.findings)
                }
            }

        val isClean = allFindings.isEmpty()
        val hasSevere = allFindings.any { it.severity == "严重" }
        return MaliciousCodeScanResult(
            isClean = isClean,
            hasSevereIssues = hasSevere,
            findings = allFindings
        )
    }

    /**
     * 检查权限等级是否需要用户确认
     */
    fun needsDangerousPermissionConfirmation(manifest: ModManifest): Boolean {
        return manifest.permissionLevel == PermissionLevel.DANGEROUS
    }

    /** 主题配置解析（占位实现） */
    private fun parseThemeConfig(json: String): Map<String, Any> {
        return try {
            val type = object : com.google.gson.reflect.TypeToken<Map<String, Any>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** 自动化配置解析（占位实现） */
    private fun parseAutomationConfigs(json: String): List<Any> {
        return try {
            val type = object : com.google.gson.reflect.TypeToken<List<Any>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }
}

// ==================== 加载状态与结果类型 ====================

/** Mod加载状态 */
sealed class ModLoadState {
    object Idle : ModLoadState()
    object Scanning : ModLoadState()
    data class Installing(val fileName: String) : ModLoadState()
    data class Ready(val loadedCount: Int, val failedCount: Int) : ModLoadState()
    data class Error(val message: String) : ModLoadState()
}

/** Mod安装结果 */
sealed class ModInstallResult {
    data class Success(val mod: LoadedMod, val missingDependencies: List<String>) : ModInstallResult()
    data class Failed(val reason: String) : ModInstallResult()
    data class SecurityBlocked(val modName: String, val reason: String) : ModInstallResult()
}

/** Mod更新检测结果 */
sealed class ModUpdateResult {
    object NoUpdate : ModUpdateResult()
    data class Available(val newManifest: ModManifest, val lspackFile: File) : ModUpdateResult()
    data class Unknown(val message: String) : ModUpdateResult()
}

// ==================== 恶意代码检测结果类型 ====================

data class MaliciousCodeFinding(
    val line: Int,
    val file: String,
    val description: String,
    val severity: String,
    val snippet: String
)

data class MaliciousCodeScanResult(
    val isClean: Boolean,
    val hasSevereIssues: Boolean,
    val findings: List<MaliciousCodeFinding>
)
