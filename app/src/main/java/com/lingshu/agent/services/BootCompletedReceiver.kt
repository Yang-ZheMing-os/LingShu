package com.lingshu.agent.services

import android.app.Notification
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint

/**
 * 通知监听服务（Notification Listener Service）
 *
 * 功能：
 * 1. 监听系统通知的发布、更新、移除事件
 * 2. 提取通知内容（标题、文本、子文本、大文本等）
 * 3. 过滤并分发通知给上层模块（如语音助理读出通知、LLM自动回复等）
 * 4. 支持取消通知、发送回复等操作
 *
 * 使用：
 * - 用户需在系统设置中开启通知监听权限
 * - 已在 AndroidManifest.xml 中声明
 */
@AndroidEntryPoint
class NotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationListener"

        /** 全局实例引用 */
        @Volatile
        var instance: NotificationListener? = null
            private set

        val isConnected: Boolean
            get() = instance != null

        /** 广播Action：通知发布 */
        const val BROADCAST_NOTIFICATION_POSTED =
            "com.lingshu.agent.NOTIFICATION_POSTED"

        /** 广播Action：通知移除 */
        const val BROADCAST_NOTIFICATION_REMOVED =
            "com.lingshu.agent.NOTIFICATION_REMOVED"

        /** Extra Key */
        const val EXTRA_KEY = "extra_key"
        const val EXTRA_PACKAGE = "extra_package"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_SUB_TEXT = "extra_sub_text"
        const val EXTRA_BIG_TEXT = "extra_big_text"
        const val EXTRA_WHEN = "extra_when"
        const val EXTRA_IS_ONGOING = "extra_is_ongoing"

        /** 通知监听器接口 */
        interface NotificationObserver {
            fun onNotificationPosted(notification: NotificationInfo)
            fun onNotificationRemoved(key: String, packageName: String?)
        }

        private val observers = mutableSetOf<NotificationObserver>()

        fun registerObserver(observer: NotificationObserver) {
            observers.add(observer)
        }

        fun unregisterObserver(observer: NotificationObserver) {
            observers.remove(observer)
        }
    }

    // ==================== 通知信息数据类 ====================

    /**
     * 解析后的通知信息
     */
    data class NotificationInfo(
        val key: String,
        val packageName: String,
        val title: String?,
        val text: String?,
        val subText: String?,
        val bigText: String?,
        val whenMs: Long,
        val isOngoing: Boolean,
        val notification: Notification
    )

    // ==================== 生命周期 ====================

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "通知监听服务已连接")
        instance = this
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "通知监听服务已断开")
        instance = null
    }

    // ==================== 事件回调 ====================

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        try {
            val info = extractNotificationInfo(sbn)

            // 广播通知
            sendBroadcast(Intent(BROADCAST_NOTIFICATION_POSTED).apply {
                setPackage(packageName)
                putExtra(EXTRA_KEY, info.key)
                putExtra(EXTRA_PACKAGE, info.packageName)
                info.title?.let { putExtra(EXTRA_TITLE, it) }
                info.text?.let { putExtra(EXTRA_TEXT, it) }
                info.subText?.let { putExtra(EXTRA_SUB_TEXT, it) }
                info.bigText?.let { putExtra(EXTRA_BIG_TEXT, it) }
                putExtra(EXTRA_WHEN, info.whenMs)
                putExtra(EXTRA_IS_ONGOING, info.isOngoing)
            })

            // 回调观察者
            observers.forEach { observer ->
                try {
                    observer.onNotificationPosted(info)
                } catch (e: Exception) {
                    Log.e(TAG, "观察者回调异常", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理通知发布事件异常", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        try {
            val key = sbn.key
            val pkg = sbn.packageName

            sendBroadcast(Intent(BROADCAST_NOTIFICATION_REMOVED).apply {
                setPackage(packageName)
                putExtra(EXTRA_KEY, key)
                putExtra(EXTRA_PACKAGE, pkg)
            })

            observers.forEach { observer ->
                try {
                    observer.onNotificationRemoved(key, pkg)
                } catch (e: Exception) {
                    Log.e(TAG, "观察者回调异常", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理通知移除事件异常", e)
        }
    }

    // ==================== 通知信息提取 ====================

    /**
     * 从 StatusBarNotification 中提取关键信息
     */
    private fun extractNotificationInfo(sbn: StatusBarNotification): NotificationInfo {
        val extras: Bundle = sbn.notification.extras

        val titleCharSeq = extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)
        val textCharSeq = extras.getCharSequence(Notification.EXTRA_TEXT)
        val subTextCharSeq = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)
        val bigTextCharSeq = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)

        return NotificationInfo(
            key = sbn.key,
            packageName = sbn.packageName,
            title = titleCharSeq?.toString(),
            text = textCharSeq?.toString(),
            subText = subTextCharSeq?.toString(),
            bigText = bigTextCharSeq?.toString(),
            whenMs = sbn.notification.`when`,
            isOngoing = sbn.isOngoing,
            notification = sbn.notification
        )
    }

    // ==================== 公开操作接口 ====================

    /**
     * 获取当前所有活跃通知
     */
    fun getAllActiveNotifications(): List<NotificationInfo> {
        return try {
            val active = activeNotifications ?: return emptyList()
            active.mapNotNull { sbn ->
                try {
                    extractNotificationInfo(sbn)
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取活跃通知失败", e)
            emptyList()
        }
    }

    /**
     * 取消指定通知
     * @param key 通知的key（StatusBarNotification.key）
     */
    fun cancelNotificationByKey(key: String): Boolean {
        return try {
            cancelNotification(key)
            true
        } catch (e: Exception) {
            Log.e(TAG, "取消通知失败: ${e.message}")
            false
        }
    }

    /**
     * 取消指定包名的所有通知
     */
    fun cancelAllByPackage(packageName: String) {
        try {
            activeNotifications?.forEach { sbn ->
                if (sbn.packageName == packageName && !sbn.isOngoing) {
                    try {
                        cancelNotification(sbn.key)
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "批量取消通知失败: ${e.message}")
        }
    }

    /**
     * 按包名筛选通知
     */
    fun getNotificationsByPackage(packageName: String): List<NotificationInfo> {
        return getAllActiveNotifications().filter { it.packageName == packageName }
    }

    /**
     * 按包名+标题查找通知
     */
    fun findNotification(
        packageName: String,
        titleContains: String
    ): NotificationInfo? {
        return getAllActiveNotifications().firstOrNull { info ->
            info.packageName == packageName &&
                    info.title?.contains(titleContains, ignoreCase = true) == true
        }
    }
}
