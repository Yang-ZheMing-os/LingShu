package com.lingshu.feature.update.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import com.lingshu.core.common.error.ErrorCodes
import com.lingshu.core.common.error.Result
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.feature.update.domain.IUpdateService
import com.lingshu.feature.update.domain.UpdateInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class UpdateServiceImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gitHubApi: GitHubApi,
    @Named("github") private val okHttpClient: OkHttpClient
) : IUpdateService {

    companion object {
        private const val TAG = "UpdateService"
        private const val GITHUB_OWNER = "lingshu-ai"
        private const val GITHUB_REPO = "lingshu-app"
    }

    override suspend fun checkForUpdate(): Result<UpdateInfo> {
        return try {
            val response = gitHubApi.getLatestRelease(GITHUB_OWNER, GITHUB_REPO)
            val currentVersion = getCurrentVersion()
            val latestVersion = response.tagName.removePrefix("v")

            if (isNewerVersion(latestVersion, currentVersion)) {
                val apkAsset = response.assets.find { it.name.endsWith(".apk") }
                    ?: return Result.error(ErrorCodes.UNKNOWN_ERROR, "未找到 APK 安装包")

                val updateInfo = UpdateInfo(
                    version = latestVersion,
                    releaseNotes = response.body,
                    downloadUrl = apkAsset.browserDownloadUrl,
                    fileSize = apkAsset.size,
                    md5 = extractMd5FromReleaseNotes(response.body),
                    isRequired = response.name.contains("强制", ignoreCase = true) ||
                            response.body.contains("强制更新", ignoreCase = true)
                )
                Result.success(updateInfo)
            } else {
                Result.error(ErrorCodes.UNKNOWN_ERROR, "已是最新版本")
            }
        } catch (e: Exception) {
            LingShuLog.e(TAG, "检查更新失败", e)
            Result.error(ErrorCodes.NETWORK_UNAVAILABLE, "检查更新失败: ${e.message}", e)
        }
    }

    override suspend fun downloadUpdate(
        updateInfo: UpdateInfo,
        onProgress: (Int) -> Unit
    ): Result<File> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(updateInfo.downloadUrl)
                    .build()

                val response = okHttpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    return@withContext Result.error(
                        ErrorCodes.NETWORK_UNAVAILABLE,
                        "下载失败: HTTP ${response.code}"
                    )
                }

                val body = response.body ?: return@withContext Result.error(
                    ErrorCodes.UNKNOWN_ERROR,
                    "下载响应体为空"
                )

                val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                val apkFile = File(downloadsDir, "lingshu_update_${updateInfo.version}.apk")

                val totalBytes = body.contentLength()
                var downloadedBytes = 0L

                body.byteStream().use { input ->
                    apkFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            val progress = if (totalBytes > 0) {
                                (downloadedBytes * 100 / totalBytes).toInt()
                            } else {
                                -1
                            }
                            withContext(Dispatchers.Main) {
                                onProgress(progress)
                            }
                        }
                    }
                }

                Result.success(apkFile)
            } catch (e: Exception) {
                LingShuLog.e(TAG, "下载更新失败", e)
                Result.error(ErrorCodes.NETWORK_UNAVAILABLE, "下载失败: ${e.message}", e)
            }
        }
    }

    override suspend fun installUpdate(apkFile: File): Result<Unit> {
        return try {
            if (!apkFile.exists()) {
                return Result.error(ErrorCodes.UNKNOWN_ERROR, "APK 文件不存在")
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (!context.packageManager.canRequestPackageInstalls()) {
                        return Result.error(
                            ErrorCodes.PERMISSION_DENIED,
                            "需要开启安装未知应用权限"
                        )
                    }
                }

                val apkUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        apkFile
                    )
                } else {
                    Uri.fromFile(apkFile)
                }

                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            context.startActivity(intent)
            Result.success(Unit)
        } catch (e: Exception) {
            LingShuLog.e(TAG, "安装更新失败", e)
            Result.error(ErrorCodes.UNKNOWN_ERROR, "安装失败: ${e.message}", e)
        }
    }

    override suspend fun getCurrentVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            LingShuLog.e(TAG, "获取当前版本失败", e)
            "1.0.0"
        }
    }

    override suspend fun verifyMd5(file: File, expectedMd5: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (!file.exists()) return@withContext false

                val md = MessageDigest.getInstance("MD5")
                FileInputStream(file).use { fis ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        md.update(buffer, 0, bytesRead)
                    }
                }

                val digest = md.digest()
                val sb = StringBuilder()
                for (b in digest) {
                    sb.append(String.format("%02x", b))
                }
                val actualMd5 = sb.toString()

                LingShuLog.d(TAG, "MD5 校验: 期望=$expectedMd5, 实际=$actualMd5")
                actualMd5.equals(expectedMd5, ignoreCase = true)
            } catch (e: Exception) {
                LingShuLog.e(TAG, "MD5 校验失败", e)
                false
            }
        }
    }

    private fun isNewerVersion(newVersion: String, currentVersion: String): Boolean {
        val newParts = newVersion.split(".").map { it.toIntOrNull() ?: 0 }
        val currentParts = currentVersion.split(".").map { it.toIntOrNull() ?: 0 }

        val maxLength = maxOf(newParts.size, currentParts.size)
        for (i in 0 until maxLength) {
            val new = newParts.getOrNull(i) ?: 0
            val current = currentParts.getOrNull(i) ?: 0
            if (new > current) return true
            if (new < current) return false
        }
        return false
    }

    private fun extractMd5FromReleaseNotes(releaseNotes: String): String {
        val md5Pattern = Regex("MD5[:：]\\s*([a-fA-F0-9]{32})")
        val matchResult = md5Pattern.find(releaseNotes)
        return matchResult?.groupValues?.get(1) ?: ""
    }
}
