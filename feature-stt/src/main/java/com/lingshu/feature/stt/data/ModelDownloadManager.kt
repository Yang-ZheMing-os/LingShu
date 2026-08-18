package com.lingshu.feature.stt.data

import android.content.Context
import com.lingshu.core.common.log.LingShuLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class DownloadProgress(
    val fileName: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val isComplete: Boolean = false,
    val isDownloading: Boolean = false,
    val error: String? = null
) {
    val percent: Int
        get() = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else 0

    val downloadedMB: Float
        get() = downloadedBytes / (1024f * 1024f)

    val totalMB: Float
        get() = totalBytes / (1024f * 1024f)
}

@Singleton
class ModelDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tag = "ModelDownload"

    private val _downloadProgress = MutableStateFlow<DownloadProgress?>(null)
    val downloadProgress: StateFlow<DownloadProgress?> = _downloadProgress.asStateFlow()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val modelDir: File by lazy {
        File(context.getExternalFilesDir(null), "sherpa/sensevoice").apply { mkdirs() }
    }

    fun isModelReady(): Boolean {
        return File(modelDir, MODEL_FILE).exists() &&
                File(modelDir, TOKENS_FILE).exists() &&
                File(modelDir, VAD_FILE).exists()
    }

    fun getModelDirPath(): String = modelDir.absolutePath

    fun getModelFileSize(): Long {
        val model = File(modelDir, MODEL_FILE)
        return if (model.exists()) model.length() else 0L
    }

    suspend fun downloadAll(): Boolean = withContext(Dispatchers.IO) {
        if (isModelReady()) {
            LingShuLog.i(tag, "模型已存在，跳过下载")
            _downloadProgress.value = DownloadProgress(
                fileName = "全部完成",
                downloadedBytes = 1,
                totalBytes = 1,
                isComplete = true
            )
            return@withContext true
        }

        try {
            val success = downloadFile(TOKENS_URL, TOKENS_FILE) &&
                    downloadFile(VAD_URL, VAD_FILE) &&
                    downloadFile(MODEL_URL, MODEL_FILE)

            if (success) {
                LingShuLog.i(tag, "所有模型文件下载完成")
                _downloadProgress.value = DownloadProgress(
                    fileName = "全部完成",
                    downloadedBytes = 1,
                    totalBytes = 1,
                    isComplete = true,
                    isDownloading = false
                )
            }
            success
        } catch (e: Exception) {
            LingShuLog.e(tag, "模型下载失败", e)
            _downloadProgress.value = DownloadProgress(
                fileName = "下载失败",
                downloadedBytes = 0,
                totalBytes = 0,
                error = e.message ?: "未知错误"
            )
            false
        }
    }

    private suspend fun downloadFile(url: String, fileName: String): Boolean {
        val targetFile = File(modelDir, fileName)
        if (targetFile.exists() && targetFile.length() > 0) {
            LingShuLog.d(tag, "$fileName 已存在 (${targetFile.length()} bytes)，跳过")
            return true
        }

        LingShuLog.i(tag, "开始下载: $fileName from $url")
        _downloadProgress.value = DownloadProgress(
            fileName = fileName,
            downloadedBytes = 0,
            totalBytes = 0,
            isDownloading = true
        )

        val tempFile = File(modelDir, "$fileName.tmp")

        return try {
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: ${response.message}")
            }

            val body = response.body ?: throw Exception("响应体为空")
            val totalBytes = body.contentLength()

            _downloadProgress.value = DownloadProgress(
                fileName = fileName,
                downloadedBytes = 0,
                totalBytes = totalBytes,
                isDownloading = true
            )

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var downloaded = 0L
                    var lastReport = 0L

                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read

                        if (downloaded - lastReport > 512 * 1024) {
                            lastReport = downloaded
                            _downloadProgress.value = DownloadProgress(
                                fileName = fileName,
                                downloadedBytes = downloaded,
                                totalBytes = totalBytes,
                                isDownloading = true
                            )
                        }
                    }
                    output.flush()
                }
            }

            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            LingShuLog.i(tag, "$fileName 下载完成 (${targetFile.length()} bytes)")
            true
        } catch (e: Exception) {
            LingShuLog.e(tag, "下载 $fileName 失败", e)
            tempFile.delete()
            _downloadProgress.value = DownloadProgress(
                fileName = fileName,
                downloadedBytes = 0,
                totalBytes = 0,
                error = "${fileName}: ${e.message}"
            )
            false
        }
    }

    fun cancelDownload() {
        httpClient.dispatcher.cancelAll()
        _downloadProgress.value = null
    }

    fun clearProgress() {
        _downloadProgress.value = null
    }

    companion object {
        private const val MODEL_FILE = "model.int8.onnx"
        private const val TOKENS_FILE = "tokens.txt"
        private const val VAD_FILE = "silero_vad.onnx"

        private const val HF_MIRROR = "https://hf-mirror.com"
        private const val HF_REPO = "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17"

        private const val MODEL_URL = "$HF_MIRROR/$HF_REPO/resolve/main/model.int8.onnx"
        private const val TOKENS_URL = "$HF_MIRROR/$HF_REPO/resolve/main/tokens.txt"
        private const val VAD_URL = "$HF_MIRROR/$HF_REPO/resolve/main/silero_vad.onnx"
    }
}
