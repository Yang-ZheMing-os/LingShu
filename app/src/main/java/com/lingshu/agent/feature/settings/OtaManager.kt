package com.lingshu.agent.feature.settings

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OTA 更新管理器
 *
 * 完整工作流：
 * ┌──────────────┐    ┌──────────────┐    ┌──────────────┐    ┌──────────────┐
 * │ 检查更新     │───▶│ 发现新版本   │───▶│ 后台下载APK  │───▶│ 安装APK     │
 * │ (启动时)     │    │ → 弹窗通知   │    │ → 进度显示   │    │ → MD5校验   │
 * └──────────────┘    └──────────────┘    └──────────────┘    └──────────────┘
 *
 * 默认更新源：https://api.github.com/repos/lingshu/lingshu-app/releases/latest
 * 用户可在设置中自定义更新源 URL
 */
@Singleton
class OtaManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "OtaManager"
        const val DEFAULT_UPDATE_URL =
            "https://api.github.com/repos/lingshu/lingshu-app/releases/latest"
    }

    // ==================== 协程 ====================
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ==================== 状态 ====================

    /** 是否有更新可用 */
    private val _updateAvailable = MutableStateFlow(false)
    val updateAvailable: StateFlow<Boolean> = _updateAvailable.asStateFlow()

    /** 最新版本信息 */
    private val _latestVersion = MutableStateFlow<VersionInfo?>(null)
    val latestVersion: StateFlow<VersionInfo?> = _latestVersion.asStateFlow()

    /** 下载进度 (0-100) */
    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress: StateFlow<Int> = _downloadProgress.asStateFlow()

    /** 是否正在下载 */
    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    /** OTA 事件 */
    private val _events = MutableSharedFlow<OtaEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<OtaEvent> = _events.asSharedFlow()

    /** 当前使用的更新源 URL */
    private var updateUrl: String = DEFAULT_UPDATE_URL

    /** 下载管理器引用 */
    private var downloadId: Long = -1L

    /** 下载完成广播接收器 */
    private var downloadReceiver: BroadcastReceiver? = null

    /** 下载的 APK 文件路径 */
    private var downloadedApkPath: String? = null

    // ==================== 更新源配置 ====================

    /**
     * 设置自定义更新源 URL
     */
    fun setUpdateUrl(url: String) {
        updateUrl = url.takeIf { it.isNotBlank() } ?: DEFAULT_UPDATE_URL
        Log.d(TAG, "更新源已设置为：$updateUrl")
    }

    fun getUpdateUrl(): String = updateUrl

    // ==================== 检查更新 ====================

    /**
     * 启动时检查更新
     *
     * 解析 GitHub Releases API 返回的 JSON，提取：
     * - tag_name → 版本号
     * - name → 版本名称
     * - body → 更新日志
     * - assets[0].browser_download_url → APK 下载地址
     * - assets[0].size → 文件大小
     */
    suspend fun checkForUpdate(): CheckResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "检查更新：$updateUrl")
            val url = URL(updateUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000

            if (conn.responseCode != 200) {
                Log.w(TAG, "更新检查失败，HTTP ${conn.responseCode}")
                return@withContext CheckResult.Failed("HTTP ${conn.responseCode}")
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)

            val tagName = json.optString("tag_name", "")
            val versionName = json.optString("name", tagName)
            val changelog = json.optString("body", "")
            val htmlUrl = json.optString("html_url", "")

            // 提取 APK 下载地址
            val assets = json.optJSONArray("assets")
            var apkUrl: String? = null
            var apkSize: Long = 0L

            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk") || name.endsWith(".APK")) {
                        apkUrl = asset.optString("browser_download_url", "")
                        apkSize = asset.optLong("size", 0L)
                        break
                    }
                }
            }

            if (tagName.isEmpty() || apkUrl == null) {
                Log.w(TAG, "未找到APK资源")
                return@withContext CheckResult.NoUpdate
            }

            // 比较版本号
            val currentVersion = getCurrentVersion()
            if (tagName <= currentVersion) {
                Log.d(TAG, "当前已是最新版本：$currentVersion >= $tagName")
                return@withContext CheckResult.NoUpdate
            }

            val info = VersionInfo(
                tagName = tagName,
                name = versionName,
                changelog = changelog,
                apkUrl = apkUrl,
                apkSize = apkSize,
                htmlUrl = htmlUrl
            )

            _latestVersion.value = info
            _updateAvailable.value = true
            _events.emit(OtaEvent.UpdateAvailable(info))

            Log.i(TAG, "发现新版本：$tagName")
            CheckResult.UpdateAvailable(info)
        } catch (e: Exception) {
            Log.e(TAG, "更新检查异常: ${e.message}", e)
            CheckResult.Failed(e.message ?: "未知错误")
        }
    }

    // ==================== 下载 APK ====================

    /**
     * 后台下载 APK
     *
     * @param apkUrl APK 下载地址
     * @param expectedMd5 可选：期望的 MD5 值（用于下载后校验）
     * @return true 表示下载已启动
     */
    fun downloadApk(apkUrl: String, expectedMd5: String? = null): Boolean {
        if (_isDownloading.value) {
            Log.w(TAG, "已有下载任务运行中")
            return false
        }

        return try {
            // 注册下载完成广播
            registerDownloadReceiver(expectedMd5)

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(apkUrl)).apply {
                setTitle("灵枢更新")
                setDescription("正在下载最新版本...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "lingshu_update.apk"
                )
                setAllowedOverMetered(true)
                setAllowedOverRoaming(false)
            }

            downloadId = downloadManager.enqueue(request)
            _isDownloading.value = true

            // 轮询下载进度
            scope.launch {
                pollDownloadProgress(downloadManager)
            }

            Log.i(TAG, "APK下载已启动，downloadId=$downloadId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "启动下载失败: ${e.message}", e)
            scope.launch { _events.emit(OtaEvent.DownloadFailed(e.message ?: "下载失败")) }
            false
        }
    }

    /**
     * 轮询下载进度
     */
    private suspend fun pollDownloadProgress(dm: DownloadManager) {
        while (_isDownloading.value) {
            kotlinx.coroutines.delay(500)
            val query = DownloadManager.Query().setFilterById(downloadId)
            val cursor = dm.query(query)
            if (cursor != null && cursor.moveToFirst()) {
                val bytesDownloaded = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                )
                val bytesTotal = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                )
                val status = cursor.getInt(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                )
                cursor.close()

                if (bytesTotal > 0) {
                    val percent = (bytesDownloaded * 100 / bytesTotal).toInt()
                    _downloadProgress.value = percent
                }

                if (status == DownloadManager.STATUS_SUCCESSFUL) break
                if (status == DownloadManager.STATUS_FAILED) {
                    _isDownloading.value = false
                    _events.emit(OtaEvent.DownloadFailed("下载失败"))
                    break
                }
            } else {
                cursor?.close()
            }
        }
    }

    /**
     * 注册下载完成广播（监听系统 DownloadManager 完成事件）
     */
    private fun registerDownloadReceiver(expectedMd5: String?) {
        unregisterDownloadReceiver()

        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                scope.launch {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
                if (id != downloadId) return@launch

                _isDownloading.value = false
                _downloadProgress.value = 100

                // 获取下载文件路径
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = dm.query(query)
                if (cursor != null && cursor.moveToFirst()) {
                    val status = cursor.getInt(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                    )
                    val localUri = cursor.getString(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)
                    )
                    cursor.close()

                    if (status == DownloadManager.STATUS_SUCCESSFUL && localUri != null) {
                        val apkFile = File(Uri.parse(localUri).path ?: "")
                        downloadedApkPath = apkFile.absolutePath

                        // MD5 校验
                        if (expectedMd5 != null) {
                            val actualMd5 = computeMd5(apkFile)
                            if (!actualMd5.equals(expectedMd5, ignoreCase = true)) {
                                _events.emit(OtaEvent.Md5Mismatch(expectedMd5, actualMd5))
                                Log.e(TAG, "MD5校验失败: 期望=$expectedMd5, 实际=$actualMd5")
                                return@launch
                            }
                        }

                        _events.emit(OtaEvent.DownloadComplete(apkFile.absolutePath))
                        Log.i(TAG, "APK下载完成: ${apkFile.absolutePath}")
                    } else {
                        _events.emit(OtaEvent.DownloadFailed("下载状态异常：$status"))
                    }
                } else {
                    cursor?.close()
                    _events.emit(OtaEvent.DownloadFailed("无法获取下载文件路径"))
                }
            }
            }
        }

        context.registerReceiver(
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            Context.RECEIVER_EXPORTED
        )
    }

    private fun unregisterDownloadReceiver() {
        downloadReceiver?.let {
            runCatching { context.unregisterReceiver(it) }
        }
        downloadReceiver = null
    }

    // ==================== 安装 APK ====================

    /**
     * 安装已下载的 APK
     *
     * Android 8.0+ 使用 FileProvider + ACTION_INSTALL_PACKAGE intent
     * 低版本使用 ACTION_VIEW
     */
    fun installApk(apkPath: String): Boolean {
        return try {
            val file = File(apkPath)
            if (!file.exists()) {
                scope.launch { _events.emit(OtaEvent.InstallFailed("APK文件不存在: $apkPath")) }
                return false
            }

            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Android 8.0+ 需要 REQUEST_INSTALL_PACKAGES 权限
                if (!context.packageManager.canRequestPackageInstalls()) {
                    // 引导用户开启"安装未知应用"权限
                    val settingsIntent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(settingsIntent)
                    scope.launch { _events.emit(OtaEvent.InstallFailed("请先允许安装未知应用")) }
                    return false
                }

                val apkUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                val apkUri = Uri.fromFile(file)
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }

            context.startActivity(intent)
            scope.launch { _events.emit(OtaEvent.InstallStarted(apkPath)) }
            Log.i(TAG, "APK安装已启动: $apkPath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "安装APK失败: ${e.message}", e)
            scope.launch { _events.emit(OtaEvent.InstallFailed(e.message ?: "安装失败")) }
            false
        }
    }

    /**
     * 安装最近下载完成的 APK
     */
    fun installDownloadedApk(): Boolean {
        val path = downloadedApkPath
        return if (path != null) installApk(path) else false
    }

    // ==================== 工具方法 ====================

    /** 读取当前 App 版本 */
    private fun getCurrentVersion(): String {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: "0.0.0"
        } catch (e: Exception) {
            "0.0.0"
        }
    }

    /** 计算文件 MD5 */
    private fun computeMd5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var len: Int
            while (fis.read(buffer).also { len = it } != -1) {
                digest.update(buffer, 0, len)
            }
        }
        return BigInteger(1, digest.digest()).toString(16).padStart(32, '0')
    }
}

// ==================== 数据类 ====================

/** 版本信息 */
data class VersionInfo(
    val tagName: String,
    val name: String,
    val changelog: String,
    val apkUrl: String,
    val apkSize: Long,
    val htmlUrl: String
)

/** 检查结果 */
sealed class CheckResult {
    object NoUpdate : CheckResult()
    data class UpdateAvailable(val info: VersionInfo) : CheckResult()
    data class Failed(val reason: String) : CheckResult()
}

/** OTA 事件 */
sealed class OtaEvent {
    data class UpdateAvailable(val info: VersionInfo) : OtaEvent()
    data class DownloadFailed(val reason: String) : OtaEvent()
    data class DownloadComplete(val apkPath: String) : OtaEvent()
    data class Md5Mismatch(val expected: String, val actual: String) : OtaEvent()
    data class InstallStarted(val apkPath: String) : OtaEvent()
    data class InstallFailed(val reason: String) : OtaEvent()
}
