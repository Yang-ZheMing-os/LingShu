package com.lingshu.agent.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 控件查找结果
 */
sealed class FindResult {
    data class Found(val node: AccessibilityNodeInfo) : FindResult()
    data class NotFound(val reason: String) : FindResult()
}

/**
 * P4 无障碍服务 — 规格书对标实现：
 * - 控件查找优先级：文本精确匹配 → 文本包含 → View ID → 类名 → 坐标区域 → 失败
 * - 执行超时15秒
 * - 服务断开后自动引导重新开启
 */
@AndroidEntryPoint
class LingShuAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "LingShuA11yService"

        /** 无障碍操作执行超时（毫秒），规格书要求15秒 */
        const val EXECUTION_TIMEOUT_MS = 15_000L

        /** 外部被关闭后重新开启的状态标记 */
        const val EXTRA_WAS_DISABLED = "lingshu_service_was_disabled"

        @Volatile
        var instance: LingShuAccessibilityService? = null
            private set

        @Volatile
        var wasDisabled: Boolean = false
            private set

        fun isConnected(): Boolean = instance != null

        /**
         * 检查无障碍服务是否已启用。
         * 返回 false 时调用方应调用 generateEnableGuideIntent() 引导用户开启。
         */
        fun isServiceEnabled(): Boolean {
            return instance != null
        }

        /**
         * 生成打开无障碍设置的 Intent，用于引导用户重新开启。
         */
        fun generateEnableGuideIntent(): Intent {
            return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }

        interface ConnectionListener {
            fun onConnected()
            fun onDisconnected()
        }

        private val listeners = mutableListOf<ConnectionListener>()

        fun registerListener(listener: ConnectionListener) {
            synchronized(listeners) {
                if (!listeners.contains(listener)) {
                    listeners.add(listener)
                    if (isConnected()) listener.onConnected()
                }
            }
        }

        fun unregisterListener(listener: ConnectionListener) {
            synchronized(listeners) {
                listeners.remove(listener)
            }
        }

        private fun notifyConnected() {
            synchronized(listeners) {
                listeners.forEach {
                    runCatching { it.onConnected() }
                }
            }
        }

        private fun notifyDisconnected() {
            synchronized(listeners) {
                listeners.forEach {
                    runCatching { it.onDisconnected() }
                }
            }
        }
    }

    interface EventListener {
        fun onEvent(event: AccessibilityEvent, eventType: Int, packageName: String?)
    }

    private val eventListeners = mutableListOf<EventListener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    var currentPackageName: String? = null
        private set
    var currentActivityName: String? = null
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "AccessibilityService connected")
        instance = this
        wasDisabled = false
        notifyConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                currentPackageName = event.packageName?.toString()
                currentActivityName = event.className?.toString()
            }
        }

        val snapshotType = event.eventType
        val snapshotPkg = event.packageName?.toString()

        synchronized(eventListeners) {
            eventListeners.forEach { listener ->
                runCatching {
                    mainHandler.post {
                        listener.onEvent(event, snapshotType, snapshotPkg)
                    }
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.i(TAG, "AccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "AccessibilityService destroyed — 服务被禁用，需引导用户重新开启")
        wasDisabled = true
        instance = null
        notifyDisconnected()
    }

    fun addEventListener(listener: EventListener) {
        synchronized(eventListeners) {
            if (!eventListeners.contains(listener)) {
                eventListeners.add(listener)
            }
        }
    }

    fun removeEventListener(listener: EventListener) {
        synchronized(eventListeners) {
            eventListeners.remove(listener)
        }
    }

    // ==================== 控件查找（P4 优先级链） ====================

    /**
     * 按优先级查找控件：
     * 1. 文本精确匹配
     * 2. 文本包含
     * 3. View ID 匹配
     * 4. 类名匹配
     * 5. 坐标区域内首个可点击节点
     * 6. 失败
     */
    fun findNodeWithPriority(
        textExact: String? = null,
        textContains: String? = null,
        viewId: String? = null,
        className: String? = null,
        coordinateX: Float? = null,
        coordinateY: Float? = null,
        tolerance: Float = 50f
    ): FindResult {
        // 1. 文本精确匹配
        if (!textExact.isNullOrBlank()) {
            val nodes = findNodesByText(textExact, exact = true)
            if (nodes.isNotEmpty()) return FindResult.Found(nodes.first())
        }

        // 2. 文本包含匹配
        if (!textContains.isNullOrBlank()) {
            val nodes = findNodesByText(textContains, exact = false)
            if (nodes.isNotEmpty()) return FindResult.Found(nodes.first())
        }

        // 3. View ID 匹配
        if (!viewId.isNullOrBlank()) {
            val nodes = findNodesById(viewId)
            if (nodes.isNotEmpty()) return FindResult.Found(nodes.first())
        }

        // 4. 类名匹配
        if (!className.isNullOrBlank()) {
            val nodes = findNodesByClass(className)
            if (nodes.isNotEmpty()) return FindResult.Found(nodes.first())
        }

        // 5. 坐标区域内查找（兜底）
        if (coordinateX != null && coordinateY != null) {
            val root = rootInActiveWindow
            if (root != null) {
                val all = mutableListOf<AccessibilityNodeInfo>()
                collectAllNodes(root, all)
                val candidates = all.filter { node ->
                    val rect = android.graphics.Rect()
                    node.getBoundsInScreen(rect)
                    coordinateX in (rect.left - tolerance)..(rect.right + tolerance) &&
                            coordinateY in (rect.top - tolerance)..(rect.bottom + tolerance)
                }.sortedBy { node ->
                    val r = android.graphics.Rect()
                    node.getBoundsInScreen(r)
                    val cx = (r.left + r.right) / 2f
                    val cy = (r.top + r.bottom) / 2f
                    val dx = (coordinateX - cx)
                    val dy = (coordinateY - cy)
                    dx * dx + dy * dy
                }
                if (candidates.isNotEmpty()) return FindResult.Found(candidates.first())
            }
        }

        // 6. 全部失败
        return FindResult.NotFound(
            "控件查找失败：textExact=$textExact, textContains=$textContains, " +
                    "viewId=$viewId, className=$className, coord=($coordinateX, $coordinateY)"
        )
    }

    // ==================== 带超时的操作封装（15秒超时） ====================

    /**
     * 带15秒超时的操作执行器。
     * 调用方传入 suspend block，内部在 IO 线程执行并在超过 EXECUTION_TIMEOUT_MS 后抛出超时。
     */
    suspend fun <T> executeWithTimeout(
        action: String,
        block: suspend () -> T
    ): Result<T> = withContext(Dispatchers.Default) {
        try {
            withTimeout(EXECUTION_TIMEOUT_MS) {
                block()
            }.let { Result.success(it) }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "[TIMEOUT] $action 超时 (${EXECUTION_TIMEOUT_MS / 1000}s)")
            Result.failure(Exception("$action 超时 (${EXECUTION_TIMEOUT_MS / 1000}s)", e))
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] $action 失败: ${e.message}")
            Result.failure(e)
        }
    }

    // ==================== 服务自检与引导开启 ====================

    /**
     * 检测无障碍服务是否在运行。
     * 若被关闭（wasDisabled=true），返回引导 Intent。
     */
    fun checkAndGetGuide(): Pair<Boolean, Intent?> {
        if (isConnected()) return Pair(true, null)
        return Pair(false, if (wasDisabled) generateEnableGuideIntent() else null)
    }

    // ==================== 基础访问方法 ====================

    fun getRootInActiveWindowCompat(): AccessibilityNodeInfo? {
        return runCatching { rootInActiveWindow }.getOrNull()
    }

    fun performGlobalActionCompat(action: Int): Boolean {
        return runCatching { performGlobalAction(action) }.getOrDefault(false)
    }

    fun pressBack(): Boolean = performGlobalActionCompat(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalActionCompat(GLOBAL_ACTION_HOME)
    fun pressRecents(): Boolean = performGlobalActionCompat(GLOBAL_ACTION_RECENTS)
    fun openNotifications(): Boolean = performGlobalActionCompat(GLOBAL_ACTION_NOTIFICATIONS)
    fun openQuickSettings(): Boolean = performGlobalActionCompat(GLOBAL_ACTION_QUICK_SETTINGS)
    fun pressPowerDialog(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalActionCompat(GLOBAL_ACTION_POWER_DIALOG)
        } else false

    // ==================== 手势模拟 ====================

    fun dispatchClick(x: Float, y: Float, durationMs: Long = 50, callback: GestureResultCallback? = null): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply {
            moveTo(x, y)
        }
        return dispatchGesture(path, durationMs, callback)
    }

    fun dispatchLongClick(x: Float, y: Float, durationMs: Long = 700, callback: GestureResultCallback? = null): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply {
            moveTo(x, y)
        }
        return dispatchGesture(path, durationMs, callback)
    }

    fun dispatchSwipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long = 400,
        callback: GestureResultCallback? = null
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        return dispatchGesture(path, durationMs, callback)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun dispatchGesture(
        path: Path, durationMs: Long, callback: GestureResultCallback?
    ): Boolean {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, callback, null)
    }

    data class Point(val x: Float, val y: Float)
    data class Stroke(val points: List<Point>, val durationMs: Long, val startDelay: Long = 0)

    @RequiresApi(Build.VERSION_CODES.N)
    fun dispatchMultiStroke(strokes: List<Stroke>, callback: GestureResultCallback? = null): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || strokes.isEmpty()) return false
        val builder = GestureDescription.Builder()
        strokes.forEach { stroke ->
            val path = Path()
            stroke.points.forEachIndexed { i, p ->
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            builder.addStroke(GestureDescription.StrokeDescription(path, stroke.startDelay, stroke.durationMs))
        }
        return dispatchGesture(builder.build(), callback, null)
    }

    // ==================== 文本输入 ====================

    fun inputText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val args = Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text
        )
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null) {
            val result = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (result) return true
        }
        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        collectAllNodes(root, allNodes)
        for (node in allNodes) {
            if (node.isEditable || node.className?.contains("EditText") == true) {
                val r = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                if (r) return true
            }
        }
        return false
    }

    // ==================== 控件树遍历 ====================

    fun collectAllNodes(root: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        out.add(root)
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            collectAllNodes(child, out)
        }
    }

    fun findNodesByText(text: String, exact: Boolean = false): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<AccessibilityNodeInfo>()
        val all = mutableListOf<AccessibilityNodeInfo>()
        collectAllNodes(root, all)
        all.forEach { node ->
            val n = node.text?.toString()?.trim() ?: ""
            val d = node.contentDescription?.toString()?.trim() ?: ""
            val matched = if (exact) {
                n == text || d == text
            } else {
                n.contains(text, ignoreCase = true) || d.contains(text, ignoreCase = true)
            }
            if (matched) result.add(node)
        }
        return result
    }

    fun findNodesById(viewId: String): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        return runCatching {
            root.findAccessibilityNodeInfosByViewId(viewId)
        }.getOrDefault(emptyList())
    }

    fun findNodesByClass(className: String): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        val all = mutableListOf<AccessibilityNodeInfo>()
        collectAllNodes(root, all)
        return all.filter { it.className?.contains(className, ignoreCase = true) == true }
    }

    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 5) {
            if (parent.isClickable) {
                return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            parent = parent.parent
            depth++
        }
        return false
    }

    fun scrollNode(node: AccessibilityNodeInfo, forward: Boolean = true): Boolean {
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return node.performAction(action)
    }

    fun clickText(text: String, exact: Boolean = false): Boolean {
        val nodes = findNodesByText(text, exact)
        return nodes.firstOrNull()?.let { clickNode(it) } == true
    }

    // ==================== 带回调的手势 ====================

    /**
     * 带 CompletableDeferred 回调和超时的手势执行 suspend 版本。
     */
    suspend fun dispatchClickSuspend(x: Float, y: Float, durationMs: Long = 50): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                deferred.complete(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                deferred.complete(false)
            }
        }
        val dispatched = dispatchClick(x, y, durationMs, callback)
        if (!dispatched) return false
        return executeWithTimeout("点击($x, $y)") {
            withTimeout(EXECUTION_TIMEOUT_MS) { deferred.await() }
        }.getOrDefault(false)
    }

    suspend fun dispatchSwipeSuspend(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long = 400
    ): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                deferred.complete(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                deferred.complete(false)
            }
        }
        val dispatched = dispatchSwipe(startX, startY, endX, endY, durationMs, callback)
        if (!dispatched) return false
        return executeWithTimeout("滑动($startX,$startY→$endX,$endY)") {
            withTimeout(EXECUTION_TIMEOUT_MS) { deferred.await() }
        }.getOrDefault(false)
    }

    suspend fun dispatchLongClickSuspend(x: Float, y: Float, durationMs: Long = 700): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                deferred.complete(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                deferred.complete(false)
            }
        }
        val dispatched = dispatchLongClick(x, y, durationMs, callback)
        if (!dispatched) return false
        return executeWithTimeout("长按($x, $y)") {
            withTimeout(EXECUTION_TIMEOUT_MS) { deferred.await() }
        }.getOrDefault(false)
    }
}
