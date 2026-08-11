package com.lingshu.agent.feature.community

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.lingshu.agent.core.model.ModCategory
import com.lingshu.agent.core.model.ModInfo
import com.lingshu.agent.core.model.ModSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

// ==================== GitHub Releases API 数据模型 ====================

/** GitHub Release API 返回的单个 Release */
data class GitHubRelease(
    @SerializedName("tag_name") val tagName: String,
    @SerializedName("name") val name: String,
    @SerializedName("body") val body: String,
    @SerializedName("published_at") val publishedAt: String,
    @SerializedName("assets") val assets: List<GitHubAsset> = emptyList()
)

/** GitHub Release 中的附件 */
data class GitHubAsset(
    @SerializedName("name") val name: String,
    @SerializedName("browser_download_url") val downloadUrl: String,
    @SerializedName("size") val size: Long,
    @SerializedName("content_type") val contentType: String
)

/** 社区索引返回的 Mod 清单 */
data class CommunityModEntry(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("version") val version: String,
    @SerializedName("author") val author: String,
    @SerializedName("description") val description: String,
    @SerializedName("category") val category: String,
    @SerializedName("tags") val tags: List<String> = emptyList(),
    @SerializedName("rating") val rating: Float = 0f,
    @SerializedName("download_count") val downloadCount: Int = 0,
    @SerializedName("download_url") val downloadUrl: String = "",
    @SerializedName("icon_url") val iconUrl: String? = null,
    @SerializedName("readme_content") val readmeContent: String? = null
)

// ==================== 本地评分/评论模型 ====================

data class ModReview(
    val modId: String,
    val userId: String,
    val rating: Float,
    val comment: String,
    val timestamp: Long,
    val isAnonymous: Boolean
)

// ==================== 下载进度模型 ====================

data class DownloadProgress(
    val modId: String = "",
    val fileName: String = "",
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val percentage: Int = 0,
    val speedBytesPerSec: Long = 0L,
    val isActive: Boolean = false,
    val isComplete: Boolean = false,
    val error: String? = null
) {
    /** 速度格式化 (KB/s) */
    val speedKbps: String
        get() = if (speedBytesPerSec >= 1024) {
            "${speedBytesPerSec / 1024} KB/s"
        } else {
            "${speedBytesPerSec} B/s"
        }
}

// ==================== GitHub API 接口 ====================

/** 社区 Mod 源配置 */
data class ModSourceConfig(
    /** 默认索引地址 */
    val defaultUrl: String = "https://api.github.com/repos/lingshu/community/releases",
    /** 用户自定义地址（null 表示使用默认） */
    var customUrl: String? = null
) {
    val activeUrl: String get() = customUrl ?: defaultUrl
}

// ==================== ViewModel ====================

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CommunityViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        const val TAB_PERSONA = 0
        const val TAB_SCRIPT = 1
        const val TAB_THEME = 2
        const val TAB_PACK = 3
        const val TAB_MY = 4

        private const val REVIEWS_FILE_NAME = "mod_reviews.json"
        private const val TAG = "CommunityVM"
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val gson = Gson()

    // ==================== 事件流 ====================

    private val _event = MutableSharedFlow<CommunityEvent>()
    val event: Flow<CommunityEvent> = _event.asSharedFlow()

    // ==================== 状态 ====================

    private val _selectedTab = MutableStateFlow(TAB_PERSONA)
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    private val _selectedMod = MutableStateFlow<ModInfo?>(null)
    val selectedMod: StateFlow<ModInfo?> = _selectedMod.asStateFlow()

    private val _searchKeyword = MutableStateFlow("")
    val searchKeyword: StateFlow<String> = _searchKeyword.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** Mod 源配置 */
    private val _modSourceConfig = MutableStateFlow(ModSourceConfig())
    val modSourceConfig: StateFlow<ModSourceConfig> = _modSourceConfig.asStateFlow()

    /** 下载进度（按 modId 索引） */
    private val _downloadProgressMap = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val downloadProgressMap: StateFlow<Map<String, DownloadProgress>> = _downloadProgressMap.asStateFlow()

    /** 评分/评论缓存 */
    private val _reviews = MutableStateFlow<Map<String, List<ModReview>>>(emptyMap())
    val reviews: StateFlow<Map<String, List<ModReview>>> = _reviews.asStateFlow()

    // ==================== 本地已安装 Mod ====================

    private val localMods = MutableStateFlow(
        listOf(
            ModInfo(
                modId = "local_1",
                name = "温柔小姐姐人格",
                version = "1.2.0",
                author = "灵枢官方",
                description = "温柔体贴的小姐姐人设，适合日常陪伴聊天",
                category = ModCategory.PERSONA,
                source = ModSource.LOCAL,
                rating = 4.8f,
                downloadCount = 12580,
                tags = listOf("温柔", "陪伴", "治愈")
            ),
            ModInfo(
                modId = "local_2",
                name = "早安脚本包",
                version = "1.0.0",
                author = "张三",
                description = "每天早上自动播报天气、新闻、日程",
                category = ModCategory.AUTOMATION,
                source = ModSource.LOCAL,
                rating = 4.5f,
                downloadCount = 3420,
                tags = listOf("自动化", "定时")
            )
        )
    )

    /** 远程 Mod 列表（从 GitHub Releases 拉取） */
    private val remoteMods = MutableStateFlow<List<ModInfo>>(emptyList())

    // ==================== UI 状态 ====================

    val uiState: StateFlow<CommunityUiState> = combine(
        listOf(
            _selectedTab,
            _searchKeyword,
            localMods,
            remoteMods,
            _selectedMod,
            _downloadProgressMap,
            _reviews
        )
    ) { values ->
        val tab = values[0] as Int
        val keyword = values[1] as String
        val local = values[2] as List<ModInfo>
        val remote = values[3] as List<ModInfo>
        val selectedMod = values[4] as ModInfo?
        val progressMap = values[5] as Map<String, DownloadProgress>
        val reviewMap = values[6] as Map<String, List<ModReview>>
        val categoryFilter = when (tab) {
            TAB_PERSONA -> ModCategory.PERSONA
            TAB_SCRIPT -> ModCategory.AUTOMATION
            TAB_THEME -> ModCategory.THEME
            TAB_PACK -> ModCategory.DATA
            else -> null
        }

        val displayList = if (tab == TAB_MY) {
            local
        } else {
            (local + remote).filter {
                categoryFilter == null || it.category == categoryFilter
            }.distinctBy { it.modId }
        }

        val filtered = if (keyword.isNotBlank()) {
            val kw = keyword.lowercase()
            displayList.filter {
                it.name.lowercase().contains(kw) ||
                        it.author.lowercase().contains(kw) ||
                        it.description.lowercase().contains(kw) ||
                        it.tags.any { tag -> tag.lowercase().contains(kw) }
            }
        } else displayList

        // 注入下载进度和评分
        val enrichedMods = filtered.map { mod ->
            val progress = progressMap[mod.modId]
            val modReviews = reviewMap[mod.modId] ?: emptyList()
            val avgRating = if (modReviews.isNotEmpty()) {
                modReviews.map { it.rating }.average().toFloat()
            } else mod.rating
            mod.copy(rating = avgRating)
        }

        CommunityUiState(
            selectedTab = tab,
            searchKeyword = keyword,
            mods = enrichedMods,
            isDetailOpen = selectedMod != null,
            selectedMod = selectedMod,
            isLoading = _isLoading.value,
            myModCount = local.size,
            totalRemoteCount = remote.size,
            downloadProgressMap = progressMap,
            reviews = reviewMap
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CommunityUiState()
    )

    // ==================== 初始化：加载评分 ====================

    init {
        loadReviewsFromDisk()
    }

    // ==================== Tab / 搜索 ====================

    fun selectTab(tab: Int) {
        _selectedTab.value = tab
        _selectedMod.value = null
    }

    fun setSearchKeyword(keyword: String) {
        _searchKeyword.value = keyword
    }

    // ==================== Mod 源配置 ====================

    /** 更新 Mod 源地址（为空则使用默认） */
    fun updateModSourceUrl(url: String) {
        _modSourceConfig.value = _modSourceConfig.value.copy(
            customUrl = url.trim().ifBlank { null }
        )
    }

    /** 重置为默认源地址 */
    fun resetModSourceUrl() {
        _modSourceConfig.value = ModSourceConfig()
    }

    // ==================== 远程 Mod 获取 ====================

    /** 从 GitHub Releases API 拉取 Mod 列表 */
    fun refreshRemote() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val releases = withContext(Dispatchers.IO) {
                    fetchGitHubReleases(_modSourceConfig.value.activeUrl)
                }
                val mods = releases.flatMap { release -> parseReleaseToMods(release) }
                remoteMods.value = mods
                _event.emit(CommunityEvent.Refreshed)
            } catch (e: Exception) {
                _event.emit(CommunityEvent.Error("获取远程Mod失败: ${e.message}"))
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 发起 HTTP GET 请求获取 GitHub Releases
     */
    private fun fetchGitHubReleases(url: String): List<GitHubRelease> {
        try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github.v3+json")
                .build()

            val response: Response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: return emptyList()
            response.close()

            val type = object : com.google.gson.reflect.TypeToken<List<GitHubRelease>>() {}.type
            return gson.fromJson(body, type) ?: emptyList()
        } catch (e: IOException) {
            throw RuntimeException("网络请求失败: ${e.message}", e)
        }
    }

    /**
     * 将 GitHub Release 解析为 ModInfo 列表
     * 遍历 .lspack 附件，从 Release body 中解析元数据
     */
    private fun parseReleaseToMods(release: GitHubRelease): List<ModInfo> {
        val lspackAssets = release.assets.filter {
            it.name.endsWith(".lspack", ignoreCase = true)
        }
        if (lspackAssets.isEmpty()) return emptyList()

        return lspackAssets.mapIndexed { index, asset ->
            val modId = "remote_${release.tagName}_${index}"
            val category = detectCategoryFromName(asset.name, release.body)
            val tags = extractTags(release.body)

            ModInfo(
                modId = modId,
                name = release.name.ifBlank { asset.name.removeSuffix(".lspack") },
                version = release.tagName.removePrefix("v"),
                author = extractAuthor(release.body),
                description = release.body.lines().firstOrNull() ?: asset.name,
                category = category,
                source = ModSource.GITHUB,
                downloadCount = asset.size.toInt() / 1000,  // 近似值
                tags = tags,
                readmeContent = release.body
            )
        }
    }

    private fun detectCategoryFromName(name: String, body: String): ModCategory {
        val combined = "$name $body".lowercase()
        return when {
            combined.contains("主题") || combined.contains("theme") || combined.contains("ui") -> ModCategory.THEME
            combined.contains("自动化") || combined.contains("automation") || combined.contains("flow") -> ModCategory.AUTOMATION
            combined.contains("整合") || combined.contains("pack") || combined.contains("bundle") -> ModCategory.DATA
            combined.contains("人格") || combined.contains("persona") || combined.contains("角色") -> ModCategory.PERSONA
            else -> ModCategory.PERSONA
        }
    }

    private fun extractTags(body: String): List<String> {
        val tagRegex = Regex("""#(\w+)""")
        return tagRegex.findAll(body).map { it.groupValues[1] }.take(8).toList()
    }

    private fun extractAuthor(body: String): String {
        val authorRegex = Regex("""作者[:：]\s*(\S+)""")
        val match = authorRegex.find(body)
        return match?.groupValues?.get(1) ?: "未知作者"
    }

    // ==================== Mod 详情 ====================

    fun openModDetail(mod: ModInfo) {
        _selectedMod.value = mod
    }

    fun closeModDetail() {
        _selectedMod.value = null
    }

    // ==================== 下载 Mod（含进度追踪） ====================

    private val modsDownloadDir: File by lazy {
        File(context.filesDir, "mods_download").apply { if (!exists()) mkdirs() }
    }

    fun downloadMod(mod: ModInfo) {
        // 如果已经在下载中，不重复触发
        if (_downloadProgressMap.value[mod.modId]?.isActive == true) return

        viewModelScope.launch {
            _isLoading.value = true
            val downloadUrl = buildDownloadUrl(mod)

            // 初始化进度
            val initProgress = DownloadProgress(
                modId = mod.modId,
                fileName = "${mod.name}.lspack",
                isActive = true
            )
            _downloadProgressMap.value = _downloadProgressMap.value + (mod.modId to initProgress)

            try {
                val downloadedFile = withContext(Dispatchers.IO) {
                    downloadWithProgress(mod, downloadUrl) { progress ->
                        _downloadProgressMap.value =
                            _downloadProgressMap.value + (mod.modId to progress)
                    }
                }

                if (downloadedFile != null) {
                    // 下载完成
                    val finalProgress = _downloadProgressMap.value[mod.modId]!!
                        .copy(isActive = false, isComplete = true, percentage = 100)
                    _downloadProgressMap.value = _downloadProgressMap.value + (mod.modId to finalProgress)

                    val newMod = mod.copy(
                        modId = "downloaded_${mod.modId}",
                        source = ModSource.IMPORTED,
                        installedAt = System.currentTimeMillis()
                    )
                    localMods.value = localMods.value + newMod
                    _event.emit(CommunityEvent.ModDownloaded(mod.name))
                } else {
                    val errorProgress = _downloadProgressMap.value[mod.modId]!!
                        .copy(isActive = false, error = "下载失败")
                    _downloadProgressMap.value = _downloadProgressMap.value + (mod.modId to errorProgress)
                    _event.emit(CommunityEvent.Error("${mod.name} 下载失败"))
                }
            } catch (e: Exception) {
                val errorProgress = _downloadProgressMap.value[mod.modId]!!
                    .copy(isActive = false, error = e.message)
                _downloadProgressMap.value = _downloadProgressMap.value + (mod.modId to errorProgress)
                _event.emit(CommunityEvent.Error("${mod.name} 下载失败: ${e.message}"))
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun buildDownloadUrl(mod: ModInfo): String {
        // 使用 GitHub Releases 附件下载 URL
        val base = if (mod.source == ModSource.GITHUB) {
            _modSourceConfig.value.activeUrl
        } else {
            _modSourceConfig.value.defaultUrl
        }
        // 构造通用下载 URL 模板
        return "${base.removeSuffix("/releases")}/releases/download/${mod.version}/${mod.name}.lspack"
    }

    private fun downloadWithProgress(
        mod: ModInfo,
        url: String,
        onProgress: (DownloadProgress) -> Unit
    ): File? {
        try {
            val request = Request.Builder().url(url).build()
            val response = okHttpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                response.close()
                return null
            }

            val body = response.body ?: run { response.close(); return null }
            val totalBytes = body.contentLength()
            val outputFile = File(modsDownloadDir, "${mod.modId}.lspack")

            body.byteStream().use { input ->
                FileOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(8192)
                    var downloadedBytes = 0L
                    var lastSampleBytes = 0L
                    var lastSampleTime = System.currentTimeMillis()
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        val now = System.currentTimeMillis()
                        val elapsed = now - lastSampleTime
                        if (elapsed >= 500) { // 每500ms更新一次进度
                            val deltaBytes = downloadedBytes - lastSampleBytes
                            val speed = if (elapsed > 0) deltaBytes * 1000 / elapsed else 0L
                            val percentage = if (totalBytes > 0) {
                                (downloadedBytes * 100 / totalBytes).toInt()
                            } else 50

                            onProgress(
                                DownloadProgress(
                                    modId = mod.modId,
                                    fileName = "${mod.name}.lspack",
                                    downloadedBytes = downloadedBytes,
                                    totalBytes = totalBytes,
                                    percentage = percentage.coerceIn(0, 100),
                                    speedBytesPerSec = speed,
                                    isActive = true,
                                    isComplete = false
                                )
                            )

                            lastSampleBytes = downloadedBytes
                            lastSampleTime = now
                        }
                    }
                }
            }
            response.close()

            // 验证 SHA-256（可选）
            return outputFile
        } catch (e: IOException) {
            throw RuntimeException("下载异常: ${e.message}", e)
        }
    }

    // ==================== Mod 启用/禁用/卸载 ====================

    fun enableMod(mod: ModInfo) {
        viewModelScope.launch {
            val updated = localMods.value.map {
                if (it.modId == mod.modId) it.copy(enabled = true) else it
            }
            localMods.value = updated
            if (_selectedMod.value?.modId == mod.modId) {
                _selectedMod.value = _selectedMod.value?.copy(enabled = true)
            }
            _event.emit(CommunityEvent.ModEnabled(mod.name))
        }
    }

    fun disableMod(mod: ModInfo) {
        viewModelScope.launch {
            val updated = localMods.value.map {
                if (it.modId == mod.modId) it.copy(enabled = false) else it
            }
            localMods.value = updated
            if (_selectedMod.value?.modId == mod.modId) {
                _selectedMod.value = _selectedMod.value?.copy(enabled = false)
            }
            _event.emit(CommunityEvent.ModDisabled(mod.name))
        }
    }

    fun uninstallMod(mod: ModInfo) {
        viewModelScope.launch {
            localMods.value = localMods.value.filter { it.modId != mod.modId }
            closeModDetail()
            _event.emit(CommunityEvent.ModUninstalled(mod.name))
        }
    }

    fun isModInstalled(modId: Boolean): Boolean = true

    // ==================== 评分与评论（本地 SQLite 持久化） ====================

    /**
     * 提交评分/评论
     * @param isAnonymous 是否匿名分享评分
     */
    fun submitReview(modId: String, rating: Float, comment: String, isAnonymous: Boolean = false) {
        viewModelScope.launch {
            val review = ModReview(
                modId = modId,
                userId = if (isAnonymous) "anonymous" else generateUserId(),
                rating = rating.coerceIn(0f, 5f),
                comment = comment,
                timestamp = System.currentTimeMillis(),
                isAnonymous = isAnonymous
            )

            val currentReviews = _reviews.value[modId]?.toMutableList() ?: mutableListOf()
            currentReviews.add(review)
            _reviews.value = _reviews.value + (modId to currentReviews)

            // 持久化到本地文件
            withContext(Dispatchers.IO) {
                saveReviewsToDisk()
            }

            _event.emit(CommunityEvent.ReviewSubmitted(modId, rating))
        }
    }

    /** 获取某个 Mod 的平均评分 */
    fun getAverageRating(modId: String): Float {
        val modReviews = _reviews.value[modId] ?: return 0f
        if (modReviews.isEmpty()) return 0f
        return modReviews.map { it.rating }.average().toFloat()
    }

    /** 获取某个 Mod 的评论列表（按时间倒序） */
    fun getReviewsForMod(modId: String): List<ModReview> {
        return (_reviews.value[modId] ?: emptyList())
            .sortedByDescending { it.timestamp }
    }

    private fun generateUserId(): String {
        val raw = "${android.os.Build.MODEL}_${android.os.Build.SERIAL}_${context.packageName}"
        val md = MessageDigest.getInstance("MD5")
        return md.digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)
    }

    // ==================== 评分持久化 ====================

    private fun saveReviewsToDisk() {
        try {
            val reviewsFile = File(context.filesDir, REVIEWS_FILE_NAME)
            val json = gson.toJson(_reviews.value)
            reviewsFile.writeText(json, Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "保存评分失败: ${e.message}")
        }
    }

    private fun loadReviewsFromDisk() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val reviewsFile = File(context.filesDir, REVIEWS_FILE_NAME)
                if (reviewsFile.exists()) {
                    val json = reviewsFile.readText(Charsets.UTF_8)
                    val type = object : com.google.gson.reflect.TypeToken<Map<String, List<ModReview>>>() {}.type
                    val loaded: Map<String, List<ModReview>> = gson.fromJson(json, type) ?: emptyMap()
                    _reviews.value = loaded
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "加载评分失败: ${e.message}")
            }
        }
    }

    // ==================== 取消下载 ====================

    fun cancelDownload(modId: String) {
        val progress = _downloadProgressMap.value[modId] ?: return
        _downloadProgressMap.value = _downloadProgressMap.value + (modId to progress.copy(
            isActive = false,
            error = "已取消"
        ))
    }
}

// ==================== 事件 ====================

sealed class CommunityEvent {
    data class ModDownloaded(val name: String) : CommunityEvent()
    data class ModEnabled(val name: String) : CommunityEvent()
    data class ModDisabled(val name: String) : CommunityEvent()
    data class ModUninstalled(val name: String) : CommunityEvent()
    data class ReviewSubmitted(val modId: String, val rating: Float) : CommunityEvent()
    object Refreshed : CommunityEvent()
    data class Error(val message: String) : CommunityEvent()
}

data class CommunityUiState(
    val selectedTab: Int = CommunityViewModel.TAB_PERSONA,
    val searchKeyword: String = "",
    val mods: List<ModInfo> = emptyList(),
    val isDetailOpen: Boolean = false,
    val selectedMod: ModInfo? = null,
    val isLoading: Boolean = false,
    val myModCount: Int = 0,
    val totalRemoteCount: Int = 0,
    val downloadProgressMap: Map<String, DownloadProgress> = emptyMap(),
    val reviews: Map<String, List<ModReview>> = emptyMap()
)
