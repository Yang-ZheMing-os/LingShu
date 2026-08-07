package com.lingshu.agent.feature.model

/**
 * P2 模型状态信息 — ModelScreen 数据模型
 */
data class ModelStatusInfo(
    /** 模型标识 */
    val modelId: String,
    /** 模型名称 */
    val modelName: String,
    /** 模型类型（Gemma / MiniCPM-V / Qwen 等） */
    val modelType: String,
    /** 下载状态 */
    val downloadState: ModelDownloadState = ModelDownloadState.NOT_DOWNLOADED,
    /** 模型文件大小（字节） */
    val fileSizeBytes: Long = 0L,
    /** 模型版本 */
    val version: String = "",
    /** 下载进度 0.0 ~ 1.0 */
    val downloadProgress: Float = 0f,
    /** 下载速度（字节/秒） */
    val downloadSpeedBytesPerSec: Long = 0L,
    /** 下载源 URL */
    val downloadUrl: String = "",
    /** 模型本地文件路径 */
    val localFilePath: String = "",
    /** 是否启用自动更新 */
    val autoUpdateEnabled: Boolean = false,
    /** 是否已加载到内存 */
    val isLoaded: Boolean = false,
    /** 是否正在使用中 */
    val isActive: Boolean = false
) {
    /** 格式化文件大小 */
    val fileSizeFormatted: String
        get() = when {
            fileSizeBytes < 1024 -> "${fileSizeBytes} B"
            fileSizeBytes < 1024 * 1024 -> "${fileSizeBytes / 1024} KB"
            fileSizeBytes < 1024 * 1024 * 1024 -> {
                val mb = fileSizeBytes / (1024.0 * 1024.0)
                String.format("%.1f MB", mb)
            }
            else -> {
                val gb = fileSizeBytes / (1024.0 * 1024.0 * 1024.0)
                String.format("%.2f GB", gb)
            }
        }

    /** 格式化下载速度 */
    val downloadSpeedFormatted: String
        get() = when {
            downloadSpeedBytesPerSec < 1024 -> "${downloadSpeedBytesPerSec} B/s"
            downloadSpeedBytesPerSec < 1024 * 1024 -> "${downloadSpeedBytesPerSec / 1024} KB/s"
            else -> {
                val mb = downloadSpeedBytesPerSec / (1024.0 * 1024.0)
                String.format("%.1f MB/s", mb)
            }
        }

    /** 下载进度百分比 */
    val progressPercent: Int
        get() = (downloadProgress * 100).toInt().coerceIn(0, 100)
}

/**
 * 模型下载状态枚举
 */
enum class ModelDownloadState {
    /** 未下载 */
    NOT_DOWNLOADED,
    /** 下载中 */
    DOWNLOADING,
    /** 已下载（未加载） */
    DOWNLOADED,
    /** 已加载到内存 */
    LOADED,
    /** 下载失败 */
    FAILED
}

/**
 * 降级策略配置
 *
 * 对应规格书：
 * - Gemma 失败 → Qwen
 * - LiteRT 不可用 → CPU 推理
 */
data class FallbackStrategyConfig(
    /** Gemma 降级链：Gemma → Qwen → 云端API */
    val gemmaFallbackEnabled: Boolean = true,
    val gemmaFallbackChain: List<String> = listOf("gemma", "qwen", "deepseek"),

    /** LiteRT 不可用时的降级 */
    val litertFallbackToCpu: Boolean = true,

    /** Qwen 降级 */
    val qwenFallbackEnabled: Boolean = true,
    val qwenFallbackChain: List<String> = listOf("qwen", "deepseek")
)

/**
 * 模型下载源 URL 配置
 */
data class ModelDownloadSources(
    /** Gemma 4 E2B 下载源 */
    val gemmaDownloadUrl: String = "https://storage.googleapis.com/litert-models/gemma-4-e2b-int8.litert",
    /** MiniCPM-V 下载源 */
    val minicpmvDownloadUrl: String = "https://huggingface.co/openbmb/MiniCPM-V-2_6/resolve/main/minicpm-v-int4.litert",
    /** Qwen 下载源 */
    val qwenDownloadUrl: String = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf"
)
