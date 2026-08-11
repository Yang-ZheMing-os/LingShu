package com.lingshu.feature.update.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.google.gson.Gson
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.feature.update.domain.ErrorReport
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.exitProcess

@Singleton
class ErrorReportManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "ErrorReportManager"
        private const val PREFS_NAME = "error_report_prefs"
        private const val KEY_CRASH_COUNT = "crash_count"
        private const val KEY_LAST_CRASH_TIME = "last_crash_time"
        private const val CRASH_THRESHOLD = 2
        private const val CRASH_RESET_INTERVAL = 24 * 60 * 60 * 1000L
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun initCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                handleCrash(throwable)
            } catch (e: Exception) {
                LingShuLog.e(TAG, "处理崩溃时出错", e)
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun handleCrash(throwable: Throwable) {
        val currentTime = System.currentTimeMillis()
        val lastCrashTime = prefs.getLong(KEY_LAST_CRASH_TIME, 0L)
        var crashCount = prefs.getInt(KEY_CRASH_COUNT, 0)

        if (currentTime - lastCrashTime > CRASH_RESET_INTERVAL) {
            crashCount = 0
        }

        crashCount++

        prefs.edit()
            .putInt(KEY_CRASH_COUNT, crashCount)
            .putLong(KEY_LAST_CRASH_TIME, currentTime)
            .apply()

        val errorReport = createErrorReport(throwable, crashCount)
        saveErrorReport(errorReport)

        LingShuLog.e(TAG, "应用崩溃，崩溃次数: $crashCount", throwable)

        if (crashCount >= CRASH_THRESHOLD) {
            LingShuLog.i(TAG, "达到崩溃阈值，已生成错误报告")
        }
    }

    private fun createErrorReport(throwable: Throwable, crashCount: Int): ErrorReport {
        val stackTrace = android.util.Log.getStackTraceString(throwable)
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)

        return ErrorReport(
            timestamp = System.currentTimeMillis(),
            deviceModel = Build.MODEL,
            deviceBrand = Build.BRAND,
            osVersion = Build.VERSION.RELEASE,
            sdkVersion = Build.VERSION.SDK_INT,
            appVersion = packageInfo.versionName ?: "unknown",
            appVersionCode = packageInfo.versionCode,
            stackTrace = stackTrace,
            crashCount = crashCount
        )
    }

    private fun saveErrorReport(errorReport: ErrorReport) {
        try {
            val logsDir = File(context.filesDir, "logs")
            if (!logsDir.exists()) {
                logsDir.mkdirs()
            }

            val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val fileName = "error_${dateFormat.format(Date(errorReport.timestamp))}.json"
            val file = File(logsDir, fileName)

            val json = gson.toJson(errorReport)
            file.writeText(json)

            LingShuLog.d(TAG, "错误报告已保存: ${file.absolutePath}")
        } catch (e: Exception) {
            LingShuLog.e(TAG, "保存错误报告失败", e)
        }
    }

    fun getErrorReports(): List<File> {
        val logsDir = File(context.filesDir, "logs")
        if (!logsDir.exists()) return emptyList()

        return logsDir.listFiles { _, name ->
            name.startsWith("error_") && name.endsWith(".json")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun readErrorReport(file: File): ErrorReport? {
        return try {
            val json = file.readText()
            gson.fromJson(json, ErrorReport::class.java)
        } catch (e: Exception) {
            LingShuLog.e(TAG, "读取错误报告失败", e)
            null
        }
    }

    fun clearErrorReports() {
        val logsDir = File(context.filesDir, "logs")
        if (!logsDir.exists()) return

        logsDir.listFiles { _, name ->
            name.startsWith("error_") && name.endsWith(".json")
        }?.forEach { it.delete() }

        resetCrashCount()
    }

    fun resetCrashCount() {
        prefs.edit()
            .putInt(KEY_CRASH_COUNT, 0)
            .putLong(KEY_LAST_CRASH_TIME, 0L)
            .apply()
    }

    fun getCrashCount(): Int {
        val currentTime = System.currentTimeMillis()
        val lastCrashTime = prefs.getLong(KEY_LAST_CRASH_TIME, 0L)
        var crashCount = prefs.getInt(KEY_CRASH_COUNT, 0)

        if (currentTime - lastCrashTime > CRASH_RESET_INTERVAL) {
            crashCount = 0
            resetCrashCount()
        }

        return crashCount
    }

    fun hasReachedCrashThreshold(): Boolean {
        return getCrashCount() >= CRASH_THRESHOLD
    }

    fun collectAllLogs(): File? {
        return try {
            val logsDir = File(context.filesDir, "logs")
            if (!logsDir.exists()) return null

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val outputFile = File(logsDir, "all_logs_$timestamp.txt")

            val sb = StringBuilder()
            sb.appendLine("=== LingShu App 日志收集 ===")
            sb.appendLine("收集时间: ${Date()}")
            sb.appendLine("设备型号: ${Build.MODEL}")
            sb.appendLine("系统版本: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            sb.appendLine()

            val errorFiles = getErrorReports()
            sb.appendLine("错误报告数量: ${errorFiles.size}")
            sb.appendLine()

            for ((index, file) in errorFiles.withIndex()) {
                sb.appendLine("=== 错误报告 ${index + 1}: ${file.name} ===")
                sb.appendLine(file.readText())
                sb.appendLine()
            }

            outputFile.writeText(sb.toString())
            outputFile
        } catch (e: Exception) {
            LingShuLog.e(TAG, "收集日志失败", e)
            null
        }
    }
}
