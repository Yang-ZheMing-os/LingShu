package com.lingshu.feature.mod.data

import android.content.Context
import com.lingshu.core.common.error.ErrorCodes
import com.lingshu.core.common.error.Result
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.feature.mod.domain.IModService
import com.lingshu.feature.mod.domain.Mod
import com.lingshu.feature.mod.domain.ModInfo
import com.lingshu.feature.mod.domain.ModManifest
import com.lingshu.feature.mod.domain.PermissionLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.inject.Inject

// =============================================
// 脚本引擎层 - 预留 QuickJS 接入
// =============================================

interface IScriptEngine {
    suspend fun executeScript(
        script: String,
        context: Map<String, Any>,
        traceId: String = ""
    ): Result<Any>
}

/**
 * Mock 脚本引擎 - 详细日志
 */
class MockScriptEngine @Inject constructor() : IScriptEngine {
    private val executionCount = MutableStateFlow(0L)
    val execCountFlow: StateFlow<Long> = executionCount

    override suspend fun executeScript(
        script: String,
        context: Map<String, Any>,
        traceId: String
    ): Result<Any> {
        val localTraceId = if (traceId.isEmpty()) "mse_${System.currentTimeMillis()}" else traceId
        val start = System.currentTimeMillis()
        LingShuLog.d(TAG, "[$localTraceId] ===== MockScriptEngine.executeScript =====")
        LingShuLog.d(TAG, "[$localTraceId] 脚本长度: ${script.length} chars")
        LingShuLog.d(TAG, "[$localTraceId] 脚本预览(前60字): ${script.take(60)}...")
        LingShuLog.d(TAG, "[$localTraceId] 执行上下文 Keys(${context.size}): ${context.keys.toList()}")

        // 1. 安全二次校验（防御性）
        LingShuLog.d(TAG, "[$localTraceId] 脚本安全二次检查...")
        val security = runSecurityChecks(script, traceId = localTraceId)
        if (security != null) {
            LingShuLog.e(TAG, "[$localTraceId] ❌ 安全校验失败，拒绝执行: $security")
            return Result.error(ErrorCodes.MOD_INCOMPLETE, "脚本安全校验失败: $security")
        }

        // 2. 模拟执行
        delay(100)
        executionCount.value += 1

        val cost = System.currentTimeMillis() - start
        LingShuLog.d(TAG, "[$localTraceId] ✅ Mock执行成功，耗时=${cost}ms, " +
                "累计执行次数=${executionCount.value}")
        return Result.success(Unit)
    }

    private fun runSecurityChecks(script: String, traceId: String): String? {
        val checks = listOf(
            "eval(" to "禁止动态执行(eval)",
            "new Function" to "禁止动态执行(new Function)",
            "setTimeout(" to "使用受限(setTimeout)",
            "setInterval(" to "使用受限(setInterval)",
            "document.cookie" to "禁止访问cookie",
            "fetch(" to "受限API(fetch)",
            "XMLHttpRequest" to "受限API(XMLHttpRequest)"
        )
        checks.forEach { (keyword, reason) ->
            val count = keyword.toRegex().findAll(script).count()
            if (count > 0) {
                LingShuLog.w(TAG, "[$traceId]   检测到关键词 '$keyword' 出现 $count 次 -> $reason")
                when {
                    keyword == "eval(" || keyword == "new Function" || keyword == "document.cookie" -> {
                        return reason
                    }
                    else -> {
                        // 其余仅警告不拦截（可配置）
                    }
                }
            }
        }
        return null
    }

    companion object {
        private const val TAG = "MockScriptEngine"
    }
}

/** QuickJS 接口（待接入） */
interface IQuickJsEngine : IScriptEngine {
    fun loadLibrary(libCode: String): Boolean
    fun getRuntimeStats(): Map<String, Any>
    fun setApiSandbox(apiProvider: (String, List<Any>) -> Any?)
}

// =============================================
// ModServiceImpl - 核心实现 + 详细日志
// =============================================

class ModServiceImpl @Inject constructor(
    private val scriptEngine: IScriptEngine,
    @ApplicationContext private val context: Context
) : IModService {

    private val installedMods = mutableListOf<Mod>()

    /** Mod 本地安装目录 /data/data/com.lingshu/files/mods/ */
    private val modsDir: File by lazy {
        File(context.filesDir, "mods").apply {
            if (!exists()) {
                val r = mkdirs()
                LingShuLog.i(TAG, "创建 Mod 安装目录: $absolutePath, 结果=$r")
            }
        }
    }

    private val _installProgress = MutableStateFlow(0f)
    val installProgress: StateFlow<Float> = _installProgress

    // ========== 初始化 ==========
    init {
        val traceId = "modinit_${System.currentTimeMillis()}"
        LingShuLog.d(TAG, "[$traceId] ===== ModService 初始化 =====")
        LingShuLog.d(TAG, "[$traceId] 安装目录: ${modsDir.absolutePath}, 存在=${modsDir.exists()}")

        try {
            // 1. 存储状态
            val (freeBytes, freeStr) = checkStorageSpace()
            LingShuLog.i(TAG, "[$traceId] 可用存储: $freeStr (${freeBytes}bytes)")
            if (freeBytes < 100 * 1024 * 1024) {
                LingShuLog.w(TAG, "[$traceId] ⚠ 存储空间不足 100MB，Mod 安装可能失败")
            }

            // 2. 扫描本地已安装 Mod
            scanInstalledMods(traceId)

            // 3. 如果没数据，用 Mock
            if (installedMods.isEmpty()) {
                addMockMods(traceId)
            }

            LingShuLog.d(TAG, "[$traceId] ===== ModService 初始化完成，已加载 ${installedMods.size} 个 Mod =====")
            installedMods.forEach {
                LingShuLog.d(TAG, "[$traceId]   - ${it.id} | ${it.name} v${it.version} | " +
                        "enabled=${it.enabled} | 权限级别=${it.manifest.permissionLevel}")
            }
        } catch (e: Exception) {
            LingShuLog.e(TAG, "[$traceId] ModService 初始化异常", e)
            addMockMods(traceId)
        }
    }

    private fun scanInstalledMods(traceId: String) {
        LingShuLog.d(TAG, "[$traceId] 扫描本地 Mod 目录...")
        val dirs = modsDir.listFiles { it.isDirectory } ?: emptyArray()
        LingShuLog.d(TAG, "[$traceId] 发现 ${dirs.size} 个子目录")

        dirs.forEach { dir ->
            val manifest = File(dir, "manifest.json")
            if (!manifest.exists()) {
                LingShuLog.w(TAG, "[$traceId] 目录 ${dir.name} 缺少 manifest.json，跳过")
                return@forEach
            }
            LingShuLog.d(TAG, "[$traceId] 加载 Mod 目录: ${dir.name}")
            try {
                val manifestObj = parseManifest(manifest)
                val mod = Mod(
                    id = manifestObj.id,
                    name = manifestObj.name,
                    version = manifestObj.version,
                    author = manifestObj.author,
                    description = manifestObj.description,
                    enabled = getModEnabledPref(dir.name),
                    installedAt = dir.lastModified(),
                    manifest = manifestObj
                )
                installedMods.add(mod)
                LingShuLog.d(TAG, "[$traceId]   ✅ 加载成功: ${mod.name} v${mod.version}")
            } catch (e: Exception) {
                LingShuLog.e(TAG, "[$traceId]   ❌ 加载失败: ${dir.name}", e)
            }
        }
    }

    private fun addMockMods(traceId: String) {
        LingShuLog.d(TAG, "[$traceId] 使用 Mock 初始数据")
        installedMods.addAll(
            listOf(
                Mod(
                    id = "mod_001",
                    name = "天气助手",
                    version = "1.0.0",
                    author = "LingShu Team",
                    description = "提供实时天气查询功能",
                    enabled = true,
                    installedAt = System.currentTimeMillis() - 86400000 * 7,
                    manifest = ModManifest(
                        id = "mod_001",
                        name = "天气助手",
                        version = "1.0.0",
                        author = "LingShu Team",
                        description = "提供实时天气查询功能",
                        mainScript = "skills/main.js",
                        minAppVersion = "1.0.0",
                        permissions = listOf("network", "location"),
                        permissionLevel = PermissionLevel.NORMAL
                    )
                ),
                Mod(
                    id = "mod_002",
                    name = "日程管理",
                    version = "2.1.0",
                    author = "Community",
                    description = "智能日程管理和提醒",
                    enabled = false,
                    installedAt = System.currentTimeMillis() - 86400000 * 3,
                    manifest = ModManifest(
                        id = "mod_002",
                        name = "日程管理",
                        version = "2.1.0",
                        author = "Community",
                        description = "智能日程管理和提醒",
                        mainScript = "skills/main.js",
                        minAppVersion = "1.0.0",
                        permissions = listOf("notification", "calendar"),
                        permissionLevel = PermissionLevel.INTERMEDIATE
                    )
                )
            )
        )
    }

    // =============================================
    // 接口 1: installMod - 安装 .lspack
    // =============================================
    override suspend fun installMod(file: File): Result<Mod> {
        val traceId = "inst_${System.currentTimeMillis()}"
        val startTime = System.currentTimeMillis()
        _installProgress.value = 0f

        LingShuLog.i(TAG, "[$traceId] ==================== installMod 开始 ====================")
        LingShuLog.i(TAG, "[$traceId] [参数] 文件=${file.absolutePath}")
        LingShuLog.d(TAG, "[$traceId] [前置] 存在=${file.exists()}, 大小=${formatFileSize(file.length())}, " +
                "可读=${file.canRead()}")

        // ===== 步骤1：基础校验 =====
        LingShuLog.d(TAG, "[$traceId] [步骤1/7] 基础校验 ...")
        val preCheck = runPreChecks(file, traceId)
        if (preCheck.isError) return preCheck as Result.Error
        _installProgress.value = 0.1f

        // ===== 步骤2：完整性 - 扩展名校验 =====
        LingShuLog.d(TAG, "[$traceId] [步骤2/7] 包格式与 MD5 完整性校验 ...")
        if (!file.name.lowercase().endsWith(".lspack") &&
                !file.name.lowercase().endsWith(".zip")) {
            LingShuLog.e(TAG, "[$traceId]   ❌ 文件扩展错误: ${file.name}")
            return Result.error(ErrorCodes.MOD_INCOMPLETE, "Mod 格式不正确，应为 .lspack 压缩包")
        }
        val md5 = calculateMD5(file)
        LingShuLog.d(TAG, "[$traceId]   文件 MD5: $md5 (可用于对比官方发布摘要)")
        _installProgress.value = 0.2f

        // ===== 步骤3：解压到临时目录 =====
        LingShuLog.d(TAG, "[$traceId] [步骤3/7] 解压 .lspack 到临时目录 ...")
        val tempDir = File(context.cacheDir, "mod_tmp_${UUID.randomUUID().toString().take(8)}")
        val unzipResult = unzipTo(file, tempDir, traceId)
        if (unzipResult.isError) {
            cleanupDir(tempDir)
            return unzipResult as Result.Error
        }
        _installProgress.value = 0.4f

        // ===== 步骤4：manifest.json 格式校验 =====
        LingShuLog.d(TAG, "[$traceId] [步骤4/7] manifest.json 合法性校验 ...")
        val manifestFile = File(tempDir, "manifest.json")
        if (!manifestFile.exists()) {
            LingShuLog.e(TAG, "[$traceId]   ❌ 缺失 manifest.json")
            cleanupDir(tempDir)
            return Result.error(ErrorCodes.MOD_INCOMPLETE,
                "Mod 包不完整：缺失 manifest.json")
        }
        val manifest = validateManifest(manifestFile, traceId)
        if (manifest.isError) {
            cleanupDir(tempDir)
            return manifest as Result.Error
        }
        val validManifest = manifest.getOrNull()!!
        _installProgress.value = 0.55f

        // ===== 步骤5：脚本安全校验 =====
        LingShuLog.d(TAG, "[$traceId] [步骤5/7] 脚本安全校验（eval/new Function/大小/权限）...")
        val security = validateScriptsRecursively(tempDir, validManifest, traceId)
        if (security.isError) {
            cleanupDir(tempDir)
            return security as Result.Error
        }
        _installProgress.value = 0.75f

        // ===== 步骤6：高级权限用户确认 =====
        val pl = validManifest.permissionLevel
        LingShuLog.d(TAG, "[$traceId] [步骤6/7] 权限等级判定: $pl")
        if (pl == PermissionLevel.ADVANCED || pl == PermissionLevel.DANGEROUS) {
            LingShuLog.w(TAG, "[$traceId]   ⚠ Mod 请求高级/危险权限: $pl")
            LingShuLog.w(TAG, "[$traceId]   声明权限: ${validManifest.permissions}")
            LingShuLog.w(TAG, "[$traceId]   ⚠ UI 层必须弹窗让用户手动确认，否则不允许安装")
            // TODO: 这里返回一个特殊状态，让 UI 走用户二次确认
            // 暂时仅记录日志，安装流程继续（在 Mock 环境里）
        }
        _installProgress.value = 0.85f

        // ===== 步骤7：移动到正式目录 + 落库 =====
        LingShuLog.d(TAG, "[$traceId] [步骤7/7] 安装到正式目录 ${modsDir.absolutePath}/${validManifest.id} ...")
        val targetDir = File(modsDir, validManifest.id)
        if (targetDir.exists()) {
            LingShuLog.w(TAG, "[$traceId]   旧版本存在，将覆盖安装")
            cleanupDir(targetDir)
        }
        val moveOk = tempDir.renameTo(targetDir)
        if (!moveOk) {
            // renameTo 失败则 fallback 到复制
            LingShuLog.w(TAG, "[$traceId]   renameTo 失败，走复制目录策略")
            copyDir(tempDir, targetDir)
            cleanupDir(tempDir)
        }
        // 写入 manifest 到文件（保证存在）
        writeManifestIfMissing(targetDir, validManifest)

        // 加入内存
        val modId = validManifest.id
        val newMod = Mod(
            id = modId,
            name = validManifest.name,
            version = validManifest.version,
            author = validManifest.author,
            description = validManifest.description,
            enabled = (validManifest.permissionLevel == PermissionLevel.NORMAL
                    || validManifest.permissionLevel == PermissionLevel.INTERMEDIATE),
            installedAt = System.currentTimeMillis(),
            manifest = validManifest
        )
        // 覆盖已存在
        val idx = installedMods.indexOfFirst { it.id == modId }
        if (idx >= 0) installedMods[idx] = newMod else installedMods.add(newMod)
        setModEnabledPref(modId, newMod.enabled)

        _installProgress.value = 1.0f
        val cost = System.currentTimeMillis() - startTime
        LingShuLog.i(TAG, "[$traceId] ==================== installMod 成功 (${cost}ms) ====================")
        LingShuLog.i(TAG, "[$traceId] 安装摘要: id=$modId, name=${newMod.name}, v${newMod.version}, " +
                "permissionLevel=${newMod.manifest.permissionLevel}, 默认启用=${newMod.enabled}")

        return Result.success(newMod)
    }

    private fun runPreChecks(file: File, traceId: String): Result<Unit> {
        if (!file.exists()) {
            LingShuLog.e(TAG, "[$traceId]   ❌ 安装包不存在")
            return Result.error(ErrorCodes.MOD_INCOMPLETE, "安装包不存在")
        }
        if (file.length() <= 0) {
            LingShuLog.e(TAG, "[$traceId]   ❌ 安装包大小为 0")
            return Result.error(ErrorCodes.MOD_INCOMPLETE, "安装包为空")
        }
        if (file.length() > MAX_MOD_PACKAGE_SIZE) {
            LingShuLog.e(TAG, "[$traceId]   ❌ 安装包过大: ${file.length()} > $MAX_MOD_PACKAGE_SIZE (100MB)")
            return Result.error(ErrorCodes.MOD_INCOMPLETE, "安装包大小超过 100MB 限制")
        }
        val (freeBytes, _) = checkStorageSpace()
        if (freeBytes < file.length() * 5) { // 解压后约为压缩包 2~5 倍
            LingShuLog.e(TAG, "[$traceId]   ❌ 存储不足: 预计需要 ${formatFileSize(file.length() * 5)}，实际 ${formatFileSize(freeBytes)}")
            return Result.error(ErrorCodes.STORAGE_INSUFFICIENT,
                ErrorCodes.getMessage(ErrorCodes.STORAGE_INSUFFICIENT))
        }
        return Result.success(Unit)
    }

    /**
     * 解压 .lspack (zip) 到目标目录
     * 带每文件完整性检测 + 层级限制（防止 zip slip）
     */
    private fun unzipTo(zipFile: File, targetDir: File, traceId: String): Result<Unit> {
        val start = System.currentTimeMillis()
        val canonicalDest = targetDir.canonicalPath
        var entryCount = 0
        var totalSize = 0L

        return try {
            if (!targetDir.exists()) targetDir.mkdirs()
            ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
                var entry: ZipEntry?
                while (zis.nextEntry.also { entry = it } != null) {
                    val e = entry!!
                    val entryFile = File(targetDir, e.name)

                    // 防止 zip slip
                    val canonical = entryFile.canonicalPath
                    if (!canonical.startsWith(canonicalDest + File.separator) && canonical != canonicalDest) {
                        LingShuLog.e(TAG, "[$traceId]   ❌ Zip Slip 检测: 非法条目 ${e.name}")
                        return Result.error(ErrorCodes.MOD_INCOMPLETE, "Mod 包包含非法路径条目")
                    }

                    if (e.isDirectory) {
                        entryFile.mkdirs()
                    } else {
                        // 限制单个解压文件 < 10MB
                        if (e.size > MAX_SINGLE_FILE_SIZE) {
                            LingShuLog.e(TAG, "[$traceId]   ❌ 文件 ${e.name} 超大: ${e.size} > 10MB")
                            return Result.error(ErrorCodes.MOD_INCOMPLETE, "Mod 包含过大文件: ${e.name}")
                        }
                        entryFile.parentFile?.mkdirs()
                        BufferedOutputStream(FileOutputStream(entryFile)).use { bos ->
                            val buf = ByteArray(4096)
                            var r: Int
                            while (zis.read(buf).also { r = it } != -1) {
                                bos.write(buf, 0, r)
                                totalSize += r
                            }
                        }
                        entryCount++
                        LingShuLog.v(TAG, "[$traceId]   解压 OK: ${e.name} (${formatFileSize(entryFile.length())})")
                    }
                    zis.closeEntry()
                }
            }
            val cost = System.currentTimeMillis() - start
            LingShuLog.d(TAG, "[$traceId]   ✅ 解压完成: 共 $entryCount 个文件, 累计 ${formatFileSize(totalSize)}, 耗时 ${cost}ms")
            Result.success(Unit)
        } catch (e: Exception) {
            LingShuLog.e(TAG, "[$traceId]   ❌ 解压异常", e)
            Result.error(ErrorCodes.MOD_INCOMPLETE, "Mod 解压失败，请重新下载", e)
        }
    }

    // ===== manifest 校验 =====
    private fun validateManifest(file: File, traceId: String): Result<ModManifest> {
        return try {
            val content = file.readText()
            LingShuLog.d(TAG, "[$traceId]   manifest.json 大小: ${content.length} chars")
            LingShuLog.v(TAG, "[$traceId]   manifest 内容:\n$content")

            // TODO: 使用 Moshi/Kotlinx-serialization 解析
            // 这里用轻量级正则解析（Mock 环境）
            val id = extractJsonString(content, "id")
            val name = extractJsonString(content, "name")
            val version = extractJsonString(content, "version")
            val author = extractJsonString(content, "author") ?: "Unknown"
            val description = extractJsonString(content, "description") ?: ""
            val mainScript = extractJsonString(content, "mainScript") ?: "skills/main.js"
            val minAppVersion = extractJsonString(content, "minAppVersion") ?: "1.0.0"
            val permissions = extractJsonStringArray(content, "permissions")

            // 必填项
            val required = mapOf("id" to id, "name" to name, "version" to version)
            required.forEach { (k, v) ->
                if (v == null) {
                    LingShuLog.e(TAG, "[$traceId]   ❌ manifest 缺少必填字段: $k")
                    return Result.error(ErrorCodes.MOD_INCOMPLETE, "manifest 缺少 $k 字段")
                }
            }

            // ID 格式
            if (!id!!.matches(Regex("^[a-zA-Z0-9._-]+$"))) {
                LingShuLog.e(TAG, "[$traceId]   ❌ id 格式非法: $id")
                return Result.error(ErrorCodes.MOD_INCOMPLETE, "Mod ID 仅允许字母数字和 ._-")
            }

            // 权限分级
            val level = PermissionLevel.fromPermissions(permissions)
            LingShuLog.d(TAG, "[$traceId]   ✅ manifest 校验通过: id=$id, name=$name, " +
                    "v=$version, author=$author, mainScript=$mainScript, permissions=$permissions -> level=$level")

            Result.success(
                ModManifest(
                    id = id,
                    name = name!!,
                    version = version!!,
                    author = author,
                    description = description,
                    mainScript = mainScript,
                    minAppVersion = minAppVersion,
                    permissions = permissions,
                    permissionLevel = level
                )
            )
        } catch (e: Exception) {
            LingShuLog.e(TAG, "[$traceId]   ❌ manifest 解析异常", e)
            Result.error(ErrorCodes.MOD_INCOMPLETE, "manifest.json 格式不正确", e)
        }
    }

    /**
     * 递归校验所有 .js 脚本的安全
     */
    private fun validateScriptsRecursively(dir: File, manifest: ModManifest, traceId: String): Result<Unit> {
        val jsFiles = dir.walkTopDown()
            .filter { it.isFile && it.extension.equals("js", true) }
            .toList()
        LingShuLog.d(TAG, "[$traceId]   发现 ${jsFiles.size} 个 .js 脚本")

        var totalBytes = 0L
        val dangerous = mutableListOf<String>() // 危险关键词
        val forbidden = listOf(
            "eval(" to "eval 动态代码执行",
            "new Function" to "new Function 动态代码执行",
            "Function(" to "Function 构造器",
            "globalThis" to "全局对象访问(受限)",
            "process." to "Node.js process 访问",
            "java." to "Java 反射(受限)",
            "android." to "Android 反射(受限)"
        )

        jsFiles.forEachIndexed { i, f ->
            LingShuLog.d(TAG, "[$traceId]   脚本[${i+1}/${jsFiles.size}]: ${f.relativeTo(dir)} (${formatFileSize(f.length())})")
            totalBytes += f.length()

            if (f.length() > MAX_SCRIPT_SIZE) {
                LingShuLog.e(TAG, "[$traceId]   ❌ 脚本超过 1MB 限制: ${f.name} ${formatFileSize(f.length())}")
                return Result.error(ErrorCodes.MOD_INCOMPLETE,
                    "脚本过大（${f.name}）：超过 1MB 限制")
            }

            val content = runCatching { f.readText() }.getOrDefault("")
            forbidden.forEach { (keyword, reason) ->
                val count = countMatches(content, keyword)
                if (count > 0) {
                    when (keyword) {
                        "eval(", "new Function", "Function(" -> {
                            LingShuLog.e(TAG, "[$traceId]   ❌ 脚本包含不安全代码: $f.name 检测到 '$keyword' (x$count) -> $reason")
                            dangerous.add("[$f.name] 包含不安全代码: $reason (x$count)")
                        }
                        else -> {
                            LingShuLog.w(TAG, "[$traceId]   ⚠ 脚本检测到受限 API '$keyword' x$count in $f.name")
                        }
                    }
                }
            }
        }

        // 总脚本大小兜底
        if (totalBytes > MAX_TOTAL_SCRIPT_SIZE) {
            LingShuLog.e(TAG, "[$traceId]   ❌ 所有脚本累计超过 5MB: ${formatFileSize(totalBytes)}")
            return Result.error(ErrorCodes.MOD_INCOMPLETE, "Mod 脚本总大小超过 5MB 限制")
        }

        if (dangerous.isNotEmpty()) {
            LingShuLog.e(TAG, "[$traceId]   ❌ 脚本安全校验未通过：${dangerous.size} 条严重违规")
            dangerous.forEach { LingShuLog.e(TAG, "[$traceId]     - $it") }
            return Result.error(ErrorCodes.MOD_INCOMPLETE,
                "Mod 包含不安全代码:\n${dangerous.take(3).joinToString("\n")}")
        }

        LingShuLog.d(TAG, "[$traceId]   ✅ 脚本安全校验通过，脚本共 ${formatFileSize(totalBytes)}")
        return Result.success(Unit)
    }

    // =============================================
    // 接口 2/3/4: enable/disable/uninstall
    // =============================================

    override suspend fun enableMod(modId: String): Result<Unit> {
        val traceId = "enb_${System.currentTimeMillis()}"
        LingShuLog.i(TAG, "[$traceId] ===== enableMod: modId=$modId =====")
        val mod = installedMods.find { it.id == modId }
            ?: run {
                LingShuLog.e(TAG, "[$traceId] ❌ Mod 不存在")
                return Result.error(ErrorCodes.UNKNOWN_ERROR, "Mod 不存在")
            }
        // 权限检查
        if (mod.manifest.permissionLevel == PermissionLevel.DANGEROUS) {
            LingShuLog.w(TAG, "[$traceId] ⚠ Mod 请求危险权限: ${mod.manifest.permissions}")
            LingShuLog.w(TAG, "[$traceId] ⚠ 按照规范应每次启用都经用户确认（此处为 Mock，已放行）")
        }
        if (mod.manifest.permissionLevel == PermissionLevel.ADVANCED) {
            LingShuLog.d(TAG, "[$traceId] Mod 请求高级权限：${mod.manifest.permissions}")
        }

        val idx = installedMods.indexOf(mod)
        installedMods[idx] = mod.copy(enabled = true)
        setModEnabledPref(modId, true)
        LingShuLog.i(TAG, "[$traceId] ✅ ${mod.name} 已启用")
        return Result.success(Unit)
    }

    override suspend fun disableMod(modId: String): Result<Unit> {
        val traceId = "dis_${System.currentTimeMillis()}"
        LingShuLog.i(TAG, "[$traceId] ===== disableMod: modId=$modId =====")
        val mod = installedMods.find { it.id == modId }
            ?: run {
                LingShuLog.e(TAG, "[$traceId] ❌ Mod 不存在")
                return Result.error(ErrorCodes.UNKNOWN_ERROR, "Mod 不存在")
            }
        val idx = installedMods.indexOf(mod)
        installedMods[idx] = mod.copy(enabled = false)
        setModEnabledPref(modId, false)
        LingShuLog.i(TAG, "[$traceId] ✅ ${mod.name} 已禁用")
        return Result.success(Unit)
    }

    override suspend fun uninstallMod(modId: String): Result<Unit> {
        val traceId = "uni_${System.currentTimeMillis()}"
        LingShuLog.i(TAG, "[$traceId] ===== uninstallMod: modId=$modId =====")

        val it = installedMods.iterator()
        var removed: Mod? = null
        while (it.hasNext()) {
            val m = it.next()
            if (m.id == modId) {
                removed = m
                it.remove()
                break
            }
        }
        if (removed == null) {
            LingShuLog.w(TAG, "[$traceId] Mod 未在内存中找到，但仍尝试删除目录")
        } else {
            LingShuLog.d(TAG, "[$traceId] 内存中移除: ${removed.name} v${removed.version}")
        }

        val dir = File(modsDir, modId)
        if (dir.exists()) {
            LingShuLog.d(TAG, "[$traceId] 删除安装目录: ${dir.absolutePath}")
            val ok = cleanupDir(dir)
            LingShuLog.i(TAG, "[$traceId] ✅ 物理删除结果: $ok")
        } else {
            LingShuLog.w(TAG, "[$traceId] 物理目录不存在: ${dir.absolutePath}")
        }

        // 清理 SharedPreferences
        context.getSharedPreferences(PREFS_MODS, Context.MODE_PRIVATE).edit()
            .remove("enabled_$modId").apply()

        LingShuLog.i(TAG, "[$traceId] ✅ uninstallMod 完成，剩余 ${installedMods.size} 个 Mod")
        return Result.success(Unit)
    }

    // =============================================
    // 接口 5/6/7: list/fetch/download
    // =============================================

    override fun listMods(): List<Mod> {
        LingShuLog.d(TAG, "listMods -> 返回 ${installedMods.size} 个")
        return installedMods.toList()
    }

    override suspend fun fetchModList(): Result<List<ModInfo>> {
        val traceId = "fch_${System.currentTimeMillis()}"
        LingShuLog.i(TAG, "[$traceId] ===== fetchModList (GitHub Releases) =====")
        val start = System.currentTimeMillis()

        return try {
            // TODO: 真实接入 GitHub Releases API
            // val resp = githubApi.listReleases(owner="LingShuAI", repo="mods")
            // return resp.body() -> map to List<ModInfo>

            delay(600)
            val store = listOf(
                ModInfo(
                    id = "store_001",
                    name = "翻译助手",
                    version = "1.2.0",
                    author = "LingShu Team",
                    description = "多语言实时翻译",
                    downloadUrl = "https://example.com/mods/translator.lspack",
                    size = 524_288,
                    permissionLevel = PermissionLevel.NORMAL,
                    rating = 4.6f,
                    downloads = 10_234
                ),
                ModInfo(
                    id = "store_002",
                    name = "音乐播放器",
                    version = "2.0.0",
                    author = "MusicDev",
                    description = "第三方音乐播放插件",
                    downloadUrl = "https://example.com/mods/music.lspack",
                    size = 1_048_576,
                    permissionLevel = PermissionLevel.INTERMEDIATE,
                    rating = 4.2f,
                    downloads = 5_013
                ),
                ModInfo(
                    id = "store_003",
                    name = "系统控制扩展",
                    version = "1.0.0",
                    author = "PowerUser",
                    description = "高级系统控制功能（需要无障碍权限）",
                    downloadUrl = "https://example.com/mods/system.lspack",
                    size = 2_097_152,
                    permissionLevel = PermissionLevel.ADVANCED,
                    rating = 4.8f,
                    downloads = 888
                )
            )
            val cost = System.currentTimeMillis() - start
            LingShuLog.i(TAG, "[$traceId] ✅ 获取商店列表成功: ${store.size} 个，耗时=${cost}ms (Mock)")
            Result.success(store)
        } catch (e: Exception) {
            LingShuLog.e(TAG, "[$traceId] ❌ 获取商店列表失败", e)
            return Result.error(ErrorCodes.NETWORK_UNAVAILABLE,
                ErrorCodes.getMessage(ErrorCodes.NETWORK_UNAVAILABLE), e)
        }
    }

    override suspend fun downloadMod(modId: String): Result<File> {
        val traceId = "dl_${System.currentTimeMillis()}"
        val start = System.currentTimeMillis()
        LingShuLog.i(TAG, "[$traceId] ===== downloadMod: modId=$modId =====")

        return try {
            delay(1500)
            val temp = File.createTempFile("mod_${modId}", ".lspack", context.cacheDir)
            // 写入占位
            temp.writeText("MOCK MOD FILE, modId=$modId\nCreatedAt=${System.currentTimeMillis()}")

            val md5 = calculateMD5(temp)
            val cost = System.currentTimeMillis() - start
            LingShuLog.i(TAG, "[$traceId] ✅ 下载完成 (Mock): file=${temp.absolutePath}, " +
                    "size=${formatFileSize(temp.length())}, md5=$md5, 耗时=${cost}ms")
            Result.success(temp)
        } catch (e: Exception) {
            LingShuLog.e(TAG, "[$traceId] ❌ 下载失败", e)
            return Result.error(ErrorCodes.NETWORK_UNAVAILABLE,
                ErrorCodes.getMessage(ErrorCodes.NETWORK_UNAVAILABLE), e)
        }
    }

    // =============================================
    // 工具方法
    // =============================================

    private fun calculateMD5(file: File): String {
        return runCatching {
            val md = MessageDigest.getInstance("MD5")
            FileInputStream(file).use { fis ->
                val buf = ByteArray(8192)
                var len: Int
                while (fis.read(buf).also { len = it } != -1) {
                    md.update(buf, 0, len)
                }
            }
            BigInteger(1, md.digest()).toString(16).padStart(32, '0')
        }.getOrDefault("UNKNOWN")
    }

    private fun checkStorageSpace(): Pair<Long, String> {
        return runCatching {
            val s = android.os.StatFs(modsDir.absolutePath)
            s.availableBytes to formatFileSize(s.availableBytes)
        }.getOrDefault(0L to "未知")
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> "%.1fKB".format(bytes / 1024.0)
            bytes < 1024L * 1024 * 1024 -> "%.1fMB".format(bytes / 1024.0 / 1024.0)
            else -> "%.2fGB".format(bytes / 1024.0 / 1024.0 / 1024.0)
        }
    }

    private fun cleanupDir(dir: File): Boolean {
        return runCatching {
            if (dir.isDirectory) dir.listFiles()?.forEach { cleanupDir(it) }
            dir.delete()
        }.getOrDefault(false)
    }

    private fun copyDir(src: File, dst: File) {
        src.walkTopDown().forEach { f ->
            val target = File(dst, f.relativeTo(src).path)
            if (f.isDirectory) target.mkdirs()
            else {
                target.parentFile?.mkdirs()
                f.copyTo(target, overwrite = true)
            }
        }
    }

    private fun getModEnabledPref(modId: String): Boolean =
        context.getSharedPreferences(PREFS_MODS, Context.MODE_PRIVATE)
            .getBoolean("enabled_$modId", false)

    private fun setModEnabledPref(modId: String, enabled: Boolean) {
        context.getSharedPreferences(PREFS_MODS, Context.MODE_PRIVATE)
            .edit().putBoolean("enabled_$modId", enabled).apply()
    }

    private fun writeManifestIfMissing(dir: File, manifest: ModManifest) {
        val f = File(dir, "manifest.json")
        if (f.exists()) return
        runCatching {
            f.writeText(
                """
                {
                  "id": "${manifest.id}",
                  "name": "${manifest.name}",
                  "version": "${manifest.version}",
                  "author": "${manifest.author}",
                  "description": "${manifest.description}",
                  "mainScript": "${manifest.mainScript}",
                  "minAppVersion": "${manifest.minAppVersion}",
                  "permissions": ${manifest.permissions},
                  "permissionLevel": "${manifest.permissionLevel}"
                }
                """.trimIndent()
            )
        }
    }

    private fun parseManifest(file: File): ModManifest {
        val text = file.readText()
        return ModManifest(
            id = extractJsonString(text, "id") ?: file.parentFile!!.name,
            name = extractJsonString(text, "name") ?: file.parentFile!!.name,
            version = extractJsonString(text, "version") ?: "0.0.1",
            author = extractJsonString(text, "author") ?: "Unknown",
            description = extractJsonString(text, "description") ?: "",
            mainScript = extractJsonString(text, "mainScript") ?: "skills/main.js",
            minAppVersion = extractJsonString(text, "minAppVersion") ?: "1.0.0",
            permissions = extractJsonStringArray(text, "permissions"),
            permissionLevel = runCatching {
                PermissionLevel.valueOf(extractJsonString(text, "permissionLevel") ?: PermissionLevel.NORMAL.name)
            }.getOrDefault(PermissionLevel.NORMAL)
        )
    }

    private fun extractJsonString(json: String, key: String): String? {
        val regex = Regex(""""$key"\s*:\s*"([^"]*)"""")
        return regex.find(json)?.groupValues?.get(1)
    }

    private fun extractJsonStringArray(json: String, key: String): List<String> {
        val result = mutableListOf<String>()
        val regex = Regex(""""$key"\s*:\s*\[([^\]]*)\]""")
        val match = regex.find(json)?.groupValues?.get(1) ?: return emptyList()
        Regex(""""([^"]*)"""").findAll(match).forEach { result.add(it.groupValues[1]) }
        return result
    }

    private fun countMatches(src: String, key: String): Int {
        if (key.isEmpty()) return 0
        var count = 0
        var idx = src.indexOf(key)
        while (idx >= 0) {
            count++
            idx = src.indexOf(key, idx + 1)
        }
        return count
    }

    companion object {
        private const val TAG = "ModService"
        private const val PREFS_MODS = "mod_prefs"
        private const val MAX_SCRIPT_SIZE = 1L * 1024 * 1024       // 单脚本 1MB
        private const val MAX_TOTAL_SCRIPT_SIZE = 5L * 1024 * 1024  // 所有脚本 5MB
        private const val MAX_MOD_PACKAGE_SIZE = 100L * 1024 * 1024 // 压缩包 100MB
        private const val MAX_SINGLE_FILE_SIZE = 10L * 1024 * 1024  // 解压单文件 10MB
    }
}
