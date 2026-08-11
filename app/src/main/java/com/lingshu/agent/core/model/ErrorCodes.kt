package com.lingshu.agent.core.model

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 灵枢错误码体系 — 从 E-001 到 E-013 的全枚举错误码
 *
 * 每个错误码包含：
 * - code: 唯一错误码标识（E-XXX）
 * - message: 面向用户的可读错误说明
 * - suggestion: 建议的用户操作
 * - category: 错误分类（便于日志过滤）
 */
enum class ErrorCode(
    val code: String,
    val message: String,
    val suggestion: String,
    val category: ErrorCategory
) {
    // ========== 语音/音频 (E-001 ~ E-003) ==========
    E001_RECORD_PERMISSION_DENIED(
        code = "E-001",
        message = "录音权限被拒绝",
        suggestion = "请在「设置 → 权限管理」中为灵枢开启麦克风权限",
        category = ErrorCategory.AUDIO
    ),
    E002_MICROPHONE_UNAVAILABLE(
        code = "E-002",
        message = "麦克风不可用",
        suggestion = "检查麦克风硬件是否正常，或是否被其他应用占用",
        category = ErrorCategory.AUDIO
    ),
    E003_AUDIO_RECORDER_ERROR(
        code = "E-003",
        message = "录音器初始化或录制失败",
        suggestion = "重启应用后重试；如持续失败，尝试重启手机",
        category = ErrorCategory.AUDIO
    ),

    // ========== 网络/模型调用 (E-004 ~ E-006) ==========
    E004_NETWORK_UNAVAILABLE(
        code = "E-004",
        message = "网络不可用",
        suggestion = "检查 Wi-Fi 或移动数据连接后重试",
        category = ErrorCategory.NETWORK
    ),
    E005_API_CALL_FAILED(
        code = "E-005",
        message = "模型API调用失败",
        suggestion = "检查「设置 → 模型配置」中的API地址和密钥是否正确",
        category = ErrorCategory.NETWORK
    ),
    E006_API_RATE_LIMITED(
        code = "E-006",
        message = "API调用频率超限",
        suggestion = "稍等 30 秒后重试；或切换至本地模型 / 更换 API Key",
        category = ErrorCategory.NETWORK
    ),

    // ========== 文件/存储 (E-007 ~ E-008) ==========
    E007_FILE_NOT_FOUND(
        code = "E-007",
        message = "文件不存在或路径无效",
        suggestion = "检查文件是否已被移动或删除；重新导入文件",
        category = ErrorCategory.FILE
    ),
    E008_INSUFFICIENT_STORAGE(
        code = "E-008",
        message = "存储空间不足",
        suggestion = "清理设备存储空间，确保至少有 100MB 可用空间",
        category = ErrorCategory.FILE
    ),

    // ========== 自动化/无障碍 (E-009 ~ E-010) ==========
    E009_ACCESSIBILITY_DISABLED(
        code = "E-009",
        message = "无障碍服务未开启",
        suggestion = "请在「设置 → 无障碍 → 灵枢」中开启无障碍服务",
        category = ErrorCategory.AUTOMATION
    ),
    E010_AUTOMATION_TIMEOUT(
        code = "E-010",
        message = "自动化操作执行超时",
        suggestion = "检查目标应用是否正常响应；调整自动化步骤的超时设置",
        category = ErrorCategory.AUTOMATION
    ),

    // ========== Mod/脚本 (E-011) ==========
    E011_MOD_INSTALL_FAILED(
        code = "E-011",
        message = "Mod安装失败",
        suggestion = "检查 .lspack 文件是否完整、manifest.json 格式是否正确",
        category = ErrorCategory.MOD
    ),

    // ========== Health Connect (E-012) ==========
    E012_HEALTH_CONNECT_UNAVAILABLE(
        code = "E-012",
        message = "Health Connect不可用",
        suggestion = "请安装 Health Connect 应用或授予健康数据权限",
        category = ErrorCategory.HEALTH
    ),

    // ========== 通用 (E-013) ==========
    E013_UNKNOWN_ERROR(
        code = "E-013",
        message = "未知错误",
        suggestion = "请重启应用后重试；如问题持续，通过「报告问题」反馈",
        category = ErrorCategory.GENERAL
    );

    override fun toString(): String = "[$code] $message | 建议：$suggestion"

    /** 返回 JSON 格式的错误对象 */
    fun toJson(): JSONObject = JSONObject().apply {
        put("code", code)
        put("message", message)
        put("suggestion", suggestion)
        put("category", category.name)
    }

    companion object {
        /** 根据 code 字符串查找对应枚举 */
        fun fromCode(code: String): ErrorCode? = entries.find { it.code == code }

        /**
         * 生成完整的错误报告（JSON格式），包含设备信息、错误堆栈、时间戳等
         *
         * @param errors 错误条目列表
         * @param deviceInfo 可选，设备信息 JSON
         * @return JSON 字符串
         */
        fun generateErrorReport(
            errors: List<ErrorReportEntry>,
            deviceInfo: JSONObject? = null
        ): String {
            val report = JSONObject()
            report.put("app_name", "灵枢 (LingShu)")
            report.put("report_time", SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", Locale.getDefault()
            ).format(Date()))

            if (deviceInfo != null) {
                report.put("device_info", deviceInfo)
            }

            val errorArray = JSONArray()
            errors.forEach { entry ->
                errorArray.put(JSONObject().apply {
                    put("code", entry.errorCode.code)
                    put("message", entry.errorCode.message)
                    put("suggestion", entry.errorCode.suggestion)
                    put("category", entry.errorCode.category.name)
                    put("timestamp", SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()
                    ).format(Date(entry.timestamp)))
                    put("stack_trace", entry.stackTrace ?: "")
                    put("context", entry.context ?: "")
                })
            }
            report.put("errors", errorArray)
            report.put("total_count", errors.size)

            return report.toString(2)
        }

        /**
         * 将错误报告保存到本地文件
         *
         * @param reportJson 报告 JSON 字符串
         * @param outputDir 输出目录
         * @return 保存后的文件路径，失败返回 null
         */
        fun saveReportToFile(reportJson: String, outputDir: File): String? {
            return try {
                if (!outputDir.exists()) outputDir.mkdirs()
                val timestamp = SimpleDateFormat(
                    "yyyyMMdd_HHmmss", Locale.getDefault()
                ).format(Date())
                val file = File(outputDir, "linghsu_error_report_$timestamp.json")
                file.writeText(reportJson)
                file.absolutePath
            } catch (e: Exception) {
                null
            }
        }
    }
}

/** 错误分类 */
enum class ErrorCategory {
    AUDIO,
    NETWORK,
    FILE,
    AUTOMATION,
    MOD,
    HEALTH,
    GENERAL
}

/** 单条错误报告条目 */
data class ErrorReportEntry(
    val errorCode: ErrorCode,
    val timestamp: Long = System.currentTimeMillis(),
    val stackTrace: String? = null,
    val context: String? = null
)
