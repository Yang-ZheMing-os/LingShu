package com.lingshu.app

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.lingshu.core.common.log.LingShuLog
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@HiltAndroidApp
class LingShuApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        initLog()
        setupUncaughtExceptionHandler()
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
