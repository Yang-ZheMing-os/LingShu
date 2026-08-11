package com.lingshu.agent.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.lingshu.agent.feature.control.ScriptEngine
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 开机自启广播接收器（Boot Completed Receiver）
 *
 * 功能：
 * 1. 监听开机完成广播（BOOT_COMPLETED / LOCKED_BOOT_COMPLETED / MY_PACKAGE_REPLACED）
 * 2. 根据用户配置决定是否自动启动相关服务：
 *    - 唤醒词检测服务 WakeWordService
 *    - 语音助理服务 VoiceAssistantService
 *    - 健康监测服务 HealthMonitorService
 *    - 主动关怀服务 ProactiveCareService
 * 3. 支持执行用户预设的"开机自动运行脚本"
 *
 * 注意：
 * - 需要 RECEIVE_BOOT_COMPLETED 权限（已在 Manifest 中声明）
 * - 在 Android 12+ 上，一些设备厂商限制自启，需要用户手动在设置中打开自启权限
 */
@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootCompletedReceiver"

        /** 广播Action：自启设置变更，供应用内发送来模拟自启 */
        const val ACTION_APPLY_BOOT_POLICY =
            "com.lingshu.agent.APPLY_BOOT_POLICY"

        /** SharedPreferences文件名 */
        private const val PREFS_NAME = "lingshu_boot_prefs"

        /** Key: 是否允许开机自启 */
        private const val KEY_ENABLE_BOOT_AUTO_START = "enable_boot_auto_start"

        /** Key: 是否启动唤醒词服务 */
        private const val KEY_AUTO_START_WAKEWORD = "auto_start_wakeword"

        /** Key: 是否启动语音助理服务 */
        private const val KEY_AUTO_START_VOICE_ASSISTANT = "auto_start_voice_assistant"

        /** Key: 是否启动健康监测服务 */
        private const val KEY_AUTO_START_HEALTH_MONITOR = "auto_start_health_monitor"

        /** Key: 是否启动主动关怀服务 */
        private const val KEY_AUTO_START_PROACTIVE = "auto_start_proactive"

        /** Key: 开机后要执行的脚本路径（可选） */
        private const val KEY_BOOT_SCRIPT_PATH = "boot_script_path"

        /** 默认值：是否开机自启 */
        private const val DEFAULT_ENABLE_BOOT_AUTO_START = false
        private const val DEFAULT_AUTO_START_WAKEWORD = true
        private const val DEFAULT_AUTO_START_VOICE_ASSISTANT = false
        private const val DEFAULT_AUTO_START_HEALTH_MONITOR = true
        private const val DEFAULT_AUTO_START_PROACTIVE = false

        /**
         * 开机自启配置
         */
        data class BootPolicy(
            val enableAutoStart: Boolean,
            val startWakeWord: Boolean,
            val startVoiceAssistant: Boolean,
            val startHealthMonitor: Boolean,
            val startProactiveCare: Boolean,
            val bootScriptPath: String?
        )

        /**
         * 保存开机自启配置
         */
        fun saveBootPolicy(context: Context, policy: BootPolicy) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().apply {
                putBoolean(KEY_ENABLE_BOOT_AUTO_START, policy.enableAutoStart)
                putBoolean(KEY_AUTO_START_WAKEWORD, policy.startWakeWord)
                putBoolean(KEY_AUTO_START_VOICE_ASSISTANT, policy.startVoiceAssistant)
                putBoolean(KEY_AUTO_START_HEALTH_MONITOR, policy.startHealthMonitor)
                putBoolean(KEY_AUTO_START_PROACTIVE, policy.startProactiveCare)
                putString(KEY_BOOT_SCRIPT_PATH, policy.bootScriptPath)
                apply()
            }
        }

        /**
         * 读取开机自启配置
         */
        fun loadBootPolicy(context: Context): BootPolicy {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return BootPolicy(
                enableAutoStart = prefs.getBoolean(
                    KEY_ENABLE_BOOT_AUTO_START, DEFAULT_ENABLE_BOOT_AUTO_START
                ),
                startWakeWord = prefs.getBoolean(
                    KEY_AUTO_START_WAKEWORD, DEFAULT_AUTO_START_WAKEWORD
                ),
                startVoiceAssistant = prefs.getBoolean(
                    KEY_AUTO_START_VOICE_ASSISTANT, DEFAULT_AUTO_START_VOICE_ASSISTANT
                ),
                startHealthMonitor = prefs.getBoolean(
                    KEY_AUTO_START_HEALTH_MONITOR, DEFAULT_AUTO_START_HEALTH_MONITOR
                ),
                startProactiveCare = prefs.getBoolean(
                    KEY_AUTO_START_PROACTIVE, DEFAULT_AUTO_START_PROACTIVE
                ),
                bootScriptPath = prefs.getString(KEY_BOOT_SCRIPT_PATH, null)
            )
        }
    }

    @Inject
    lateinit var scriptEngine: ScriptEngine

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "收到广播: $action")

        // 只处理特定的广播Action
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_APPLY_BOOT_POLICY -> {
                applyBootPolicy(context)
            }
        }
    }

    /**
     * 根据开机配置启动相应的服务
     */
    private fun applyBootPolicy(context: Context) {
        val policy = loadBootPolicy(context)

        Log.i(TAG, "应用开机策略: enable=${policy.enableAutoStart}")

        if (!policy.enableAutoStart) {
            Log.i(TAG, "用户未开启开机自启，跳过")
            return
        }

        // 延迟启动服务，避免开机早期资源竞争
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed({
            try {
                startConfiguredServices(context, policy)
            } catch (e: Exception) {
                Log.e(TAG, "启动服务异常", e)
            }

            // 执行开机脚本（如果配置了）
            try {
                policy.bootScriptPath?.let { path ->
                    executeBootScript(context, path)
                }
            } catch (e: Exception) {
                Log.e(TAG, "执行开机脚本异常", e)
            }
        }, 5000L)
    }

    /**
     * 按配置启动各项服务
     */
    private fun startConfiguredServices(context: Context, policy: BootPolicy) {
        // 唤醒词服务
        if (policy.startWakeWord) {
            try {
                val intent = Intent(context, WakeWordService::class.java).apply {
                    action = WakeWordService.ACTION_START_SERVICE
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.i(TAG, "已请求启动 WakeWordService")
            } catch (e: Exception) {
                Log.e(TAG, "启动 WakeWordService 失败: ${e.message}")
            }
        }

        // 语音助理服务
        if (policy.startVoiceAssistant) {
            try {
                val intent = Intent(context, VoiceAssistantService::class.java).apply {
                    action = VoiceAssistantService.ACTION_START
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.i(TAG, "已请求启动 VoiceAssistantService")
            } catch (e: Exception) {
                Log.e(TAG, "启动 VoiceAssistantService 失败: ${e.message}")
            }
        }

        // 健康监测服务
        if (policy.startHealthMonitor) {
            try {
                val serviceClass = Class.forName(
                    "com.lingshu.agent.services.HealthMonitorService"
                )
                val intent = Intent(context, serviceClass)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.i(TAG, "已请求启动 HealthMonitorService")
            } catch (e: Exception) {
                Log.w(TAG, "启动 HealthMonitorService 失败（类可能不存在或未启用）: ${e.message}")
            }
        }

        // 主动关怀服务
        if (policy.startProactiveCare) {
            try {
                val serviceClass = Class.forName(
                    "com.lingshu.agent.services.ProactiveCareService"
                )
                val intent = Intent(context, serviceClass)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.i(TAG, "已请求启动 ProactiveCareService")
            } catch (e: Exception) {
                Log.w(TAG, "启动 ProactiveCareService 失败（类可能不存在或未启用）: ${e.message}")
            }
        }
    }

    /**
     * 执行开机脚本
     */
    private fun executeBootScript(context: Context, scriptPath: String) {
        try {
            val scriptFile = java.io.File(scriptPath)
            if (!scriptFile.exists()) {
                Log.w(TAG, "开机脚本不存在: $scriptPath")
                return
            }

            val scriptContent = scriptFile.readText(Charsets.UTF_8)
            val scriptName = scriptFile.nameWithoutExtension

            Log.i(TAG, "执行开机脚本: $scriptName")
            scriptEngine.execute(scriptContent, scriptName)
        } catch (e: Exception) {
            Log.e(TAG, "执行开机脚本失败: ${e.message}", e)
        }
    }
}
