package com.lingshu.core.common.error

object ErrorCodes {
    const val NETWORK_UNAVAILABLE = "E-001"
    const val SERVER_NO_RESPONSE = "E-002"
    const val API_KEY_INVALID = "E-003"
    const val STORAGE_INSUFFICIENT = "E-004"
    const val PERMISSION_DENIED = "E-005"
    const val MODEL_LOAD_FAILED = "E-006"
    const val MICROPHONE_UNAVAILABLE = "E-007"
    const val STT_FAILED = "E-008"
    const val TTS_UNAVAILABLE = "E-009"
    const val ACCESSIBILITY_DISABLED = "E-010"
    const val VOICE_CLONE_FAILED = "E-011"
    const val DOCUMENT_PARSE_FAILED = "E-012"
    const val MOD_INCOMPLETE = "E-013"
    const val UNKNOWN_ERROR = "E-014"

    fun getMessage(code: String): String = when (code) {
        NETWORK_UNAVAILABLE -> "网络未连接，请检查网络设置"
        SERVER_NO_RESPONSE -> "服务器无响应，请稍后重试"
        API_KEY_INVALID -> "API Key 无效，请检查设置"
        STORAGE_INSUFFICIENT -> "存储空间不足，请清理至少 500MB"
        PERMISSION_DENIED -> "需要相应权限才能使用此功能"
        MODEL_LOAD_FAILED -> "模型加载失败，请重新下载"
        MICROPHONE_UNAVAILABLE -> "录音设备不可用"
        STT_FAILED -> "没听清，请再说一遍"
        TTS_UNAVAILABLE -> "语音播报不可用，请检查系统设置"
        ACCESSIBILITY_DISABLED -> "需要开启无障碍服务才能使用此功能"
        VOICE_CLONE_FAILED -> "声音克隆失败，请重试"
        DOCUMENT_PARSE_FAILED -> "文档格式不支持或已损坏"
        MOD_INCOMPLETE -> "Mod 文件不完整，请重新下载"
        UNKNOWN_ERROR -> "出错了，请重试"
        else -> "出错了，请重试"
    }

    fun getSuggestion(code: String): String = when (code) {
        NETWORK_UNAVAILABLE -> "检查网络"
        SERVER_NO_RESPONSE -> "稍后重试"
        API_KEY_INVALID -> "检查 API Key"
        STORAGE_INSUFFICIENT -> "清理空间"
        PERMISSION_DENIED -> "去设置开启"
        MODEL_LOAD_FAILED -> "重新下载"
        MICROPHONE_UNAVAILABLE -> "检查麦克风"
        STT_FAILED -> "重试"
        TTS_UNAVAILABLE -> "检查 TTS 设置"
        ACCESSIBILITY_DISABLED -> "去设置开启"
        VOICE_CLONE_FAILED -> "重试"
        DOCUMENT_PARSE_FAILED -> "检查文档格式"
        MOD_INCOMPLETE -> "重新下载"
        UNKNOWN_ERROR -> "重试"
        else -> "重试"
    }
}
