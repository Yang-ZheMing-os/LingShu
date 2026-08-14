package com.lingshu.app

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.work.Configuration
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.core.common.event.di.EventBridgesStarter
import com.lingshu.feature.proactive.domain.IProactiveService
import dagger.Lazy
import javax.inject.Inject
import androidx.hilt.work.HiltWorkerFactory
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@HiltAndroidApp
class LingShuApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var eventBridgesStarter: EventBridgesStarter

    @Inject
    lateinit var proactiveServiceLazy: Lazy<IProactiveService>

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * HiltWorker 必需：提供带 HiltWorkerFactory 的 WorkManager 配置（按需初始化）。
     * 没有这个的话，@HiltWorker 注解的 ProactiveCheckWorker 无法被 Hilt 注入，
     * 会在 WorkManager 尝试实例化时抛异常 → Worker state 变 FAILED/CANCELLED → 永远不执行。
     *
     * WorkManager 2.9.0 Provider 接口是 Kotlin property（不是 Java method），
     * 所以用 override val ... get() = ... 形式。
     */
    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .build()

    override fun onCreate() {
        super.onCreate()
        instance = this
        initLog()
        setupUncaughtExceptionHandler()

        // 启动事件桥梁，打通语音闭环：唤醒->STT->对话->TTS播报
        eventBridgesStarter.startAll()

        // 主动关怀冷启动恢复：从持久化配置里读取 enabled，如果之前开过就自动起 Worker
        // 没有这一步的话，用户开了开关、划掉进程后，下一次冷启动没人调 start()，就永远不会推
        appScope.launch {
            runCatching {
                val service = proactiveServiceLazy.get()
                val config = service.getConfig()
                if (config.enabled) {
                    LingShuLog.i(TAG, "冷启动恢复主动关怀（enabled=true），自动启动 Worker")
                    service.start()
                } else {
                    LingShuLog.i(TAG, "冷启动: 主动关怀未开启（enabled=false），跳过 Worker 启动")
                }
            }.onFailure { e ->
                LingShuLog.e(TAG, "冷启动恢复主动关怀失败", e)
            }
        }

        LingShuLog.i(TAG, "Application started")
    }

    private fun initLog() {
        LingShuLog.i(TAG, "Log system initialized")
    }

    private fun setupUncaughtExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            LingShuLog.e(TAG, "Uncaught exception in thread: ${thread.name}", throwable)

            val crashCount = incrementCrashCount()
            LingShuLog.w(TAG, "Crash count: $crashCount")

            if (crashCount >= CRASH_THRESHOLD) {
                generateErrorReport(throwable)
                resetCrashCount()
            }

            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun incrementCrashCount(): Int {
        val prefs = getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE)
        val currentCount = prefs.getInt(CRASH_COUNT_KEY, 0) + 1
        prefs.edit().putInt(CRASH_COUNT_KEY, currentCount).apply()
        prefs.edit().putLong(CRASH_TIMESTAMP_KEY, System.currentTimeMillis()).apply()
        return currentCount
    }

    private fun resetCrashCount() {
        val prefs = getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE)
        prefs.edit().putInt(CRASH_COUNT_KEY, 0).apply()
    }

    private fun generateErrorReport(throwable: Throwable) {
        try {
            val timeStamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
            val reportFileName = "crash_report_$timeStamp.txt"
            val reportFile = File(getExternalFilesDir(null), reportFileName)

            PrintWriter(FileWriter(reportFile)).use { writer ->
                writer.println("=== 灵枢应用错误报告 ===")
                writer.println("时间: $timeStamp")
                writer.println("应用版本: ${packageManager.getPackageInfo(packageName, 0).versionName}")
                writer.println("设备型号: ${android.os.Build.MODEL}")
                writer.println("Android版本: ${android.os.Build.VERSION.RELEASE}")
                writer.println("SDK版本: ${android.os.Build.VERSION.SDK_INT}")
                writer.println()
                writer.println("=== 堆栈信息 ===")
                throwable.printStackTrace(writer)
                writer.println()
                writer.println("=== 系统信息 ===")
                writer.println("可用内存: ${Runtime.getRuntime().freeMemory() / 1024 / 1024} MB")
                writer.println("总内存: ${Runtime.getRuntime().totalMemory() / 1024 / 1024} MB")
                writer.println("最大内存: ${Runtime.getRuntime().maxMemory() / 1024 / 1024} MB")
            }

            LingShuLog.i(TAG, "Error report generated: ${reportFile.absolutePath}")
        } catch (e: Exception) {
            LingShuLog.e(TAG, "Failed to generate error report", e)
        }
    }

    companion object {
        private const val TAG = "LingShuApplication"
        private const val CRASH_PREFS = "crash_prefs"
        private const val CRASH_COUNT_KEY = "crash_count"
        private const val CRASH_TIMESTAMP_KEY = "crash_timestamp"
        private const val CRASH_THRESHOLD = 2

        lateinit var instance: LingShuApplication
            private set
    }
}
