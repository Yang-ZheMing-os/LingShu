package com.lingshu.agent.feature.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.lingshu.agent.services.LingShuAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 无障碍高级控制器 - 需要无障碍服务权限
 *
 * 功能列表：
 * - 模拟点击(x,y)
 * - 模拟滑动(startX,startY,endX,endY,duration)
 * - 模拟长按(x,y,duration)
 * - 多点触控
 * - 模拟文本输入
 * - 按返回键/主页键/最近任务键
 * - 读取屏幕控件树（AccessibilityNodeInfo遍历）
 * - 按文本查找控件
 * - 按ID/类名查找控件
 * - 执行控件的click/scroll/setText动作
 *
 * 依赖 LingShuAccessibilityService，需要用户在设置中开启无障碍权限
 */
@Singleton
class AccessibilityController @Inject constructor(
    @ApplicationContext private val context: android.content.Context
) {

    /** 主线程Handler，用于手势回调 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 获取当前无障碍服务实例
     * 注意：服务未启动时返回null
     */
    private fun getService(): LingShuAccessibilityService? {
        return LingShuAccessibilityService.instance
    }

    /**
     * 检查无障碍服务是否已启用
     */
    fun isServiceConnected(): Boolean {
        return getService() != null
    }

    // ==================== 手势操作 ====================

    /**
     * 手势执行回调
     */
    interface GestureResultCallback {
        fun onSuccess()
        fun onFailure(reason: String)
    }

    /**
     * 模拟点击指定坐标
     * @param x 屏幕X坐标
     * @param y 屏幕Y坐标
     * @param duration 按压持续时间（毫秒），默认50ms
     * @param callback 结果回调（可选）
     * @return 是否成功提交手势（不代表手势执行成功）
     */
    fun click(
        x: Float,
        y: Float,
        duration: Long = 50L,
        callback: GestureResultCallback? = null
    ): Boolean {
        val service = getService() ?: return false

        val path = Path().apply {
            moveTo(x, y)
        }

        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))

        val gestureCallback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                mainHandler.post { callback?.onSuccess() }
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                mainHandler.post { callback?.onFailure("手势被取消") }
            }
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            service.dispatchGesture(gestureBuilder.build(), gestureCallback, mainHandler)
        } else {
            callback?.onFailure("Android版本过低，不支持手势")
            false
        }
    }

    /**
     * 模拟长按指定坐标
     * @param x 屏幕X坐标
     * @param y 屏幕Y坐标
     * @param duration 长按持续时间（毫秒），默认500ms
     * @param callback 结果回调（可选）
     */
    fun longClick(
        x: Float,
        y: Float,
        duration: Long = 500L,
        callback: GestureResultCallback? = null
    ): Boolean {
        return click(x, y, duration, callback)
    }

    /**
     * 模拟滑动手势
     * @param startX 起点X坐标
     * @param startY 起点Y坐标
     * @param endX 终点X坐标
     * @param endY 终点Y坐标
     * @param duration 滑动持续时间（毫秒），默认300ms
     * @param callback 结果回调（可选）
     */
    fun swipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        duration: Long = 300L,
        callback: GestureResultCallback? = null
    ): Boolean {
        val service = getService() ?: return false

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))

        val gestureCallback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                mainHandler.post { callback?.onSuccess() }
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                mainHandler.post { callback?.onFailure("手势被取消") }
            }
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            service.dispatchGesture(gestureBuilder.build(), gestureCallback, mainHandler)
        } else {
            callback?.onFailure("Android版本过低，不支持手势")
            false
        }
    }

    /**
     * 多点触控手势
     * @param strokes 多点触控笔画列表，每笔包含路径、起始时间、持续时间
     * @param callback 结果回调（可选）
     */
    fun multiTouch(
        strokes: List<Triple<Path, Long, Long>>,
        callback: GestureResultCallback? = null
    ): Boolean {
        val service = getService() ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            callback?.onFailure("Android版本过低，不支持手势")
            return false
        }

        val gestureBuilder = GestureDescription.Builder()
        strokes.forEach { (path, startAt, duration) ->
            gestureBuilder.addStroke(
                GestureDescription.StrokeDescription(path, startAt, duration)
            )
        }

        val gestureCallback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                mainHandler.post { callback?.onSuccess() }
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                mainHandler.post { callback?.onFailure("手势被取消") }
            }
        }

        return service.dispatchGesture(gestureBuilder.build(), gestureCallback, mainHandler)
    }

    // ==================== 文本输入 ====================

    /**
     * 模拟文本输入
     * 找到当前聚焦的输入控件并设置文本
     * @param text 要输入的文本内容
     * @return 是否成功
     */
    fun inputText(text: String): Boolean {
        val service = getService() ?: return false
        val rootNode = service.getRootInActiveWindowCompat() ?: return false

        val focusNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val targetNode = focusNode ?: findFirstEditText(rootNode)

        if (targetNode != null) {
            val arguments = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
            val result = targetNode.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                arguments
            )
            targetNode.recycle()
            if (focusNode == null) {
                rootNode.recycle()
            }
            return result
        }
        rootNode.recycle()
        return false
    }

    /**
     * 查找第一个可输入文本的控件
     */
    private fun findFirstEditText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable || node.className?.contains("EditText", true) == true) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstEditText(child)
            if (found != null) {
                return found
            }
            child.recycle()
        }
        return null
    }

    // ==================== 系统按键 ====================

    /**
     * 按返回键
     * @return 是否成功
     */
    fun pressBack(): Boolean {
        val service = getService() ?: return false
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    /**
     * 按主页键（Home）
     * @return 是否成功
     */
    fun pressHome(): Boolean {
        val service = getService() ?: return false
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    /**
     * 打开最近任务（概览）
     * @return 是否成功
     */
    fun pressRecentApps(): Boolean {
        val service = getService() ?: return false
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
    }

    /**
     * 打开通知栏
     * @return 是否成功
     */
    fun openNotifications(): Boolean {
        val service = getService() ?: return false
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
    }

    /**
     * 打开快速设置面板
     * @return 是否成功
     */
    fun openQuickSettings(): Boolean {
        val service = getService() ?: return false
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
    }

    /**
     * 模拟电源键（亮屏/关屏）（需要Android P及以上）
     */
    fun pressPower(): Boolean {
        val service = getService() ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        } else {
            false
        }
    }

    // ==================== 控件树遍历 ====================

    /**
     * 屏幕控件节点信息
     */
    data class NodeInfo(
        /** 控件文本 */
        val text: String?,
        /** 内容描述 */
        val contentDescription: String?,
        /** 资源ID（完整ID名） */
        val viewIdResourceName: String?,
        /** 类名 */
        val className: String?,
        /** 是否可点击 */
        val isClickable: Boolean,
        /** 是否可滚动 */
        val isScrollable: Boolean,
        /** 是否可编辑 */
        val isEditable: Boolean,
        /** 是否可勾选 */
        val isCheckable: Boolean,
        /** 是否已勾选 */
        val isChecked: Boolean,
        /** 控件在屏幕上的边界（left, top, right, bottom） */
        val boundsInScreen: IntArray,
        /** 子节点数量 */
        val childCount: Int,
        /** 子节点（递归） */
        val children: List<NodeInfo>
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is NodeInfo) return false
            if (text != other.text) return false
            if (contentDescription != other.contentDescription) return false
            if (viewIdResourceName != other.viewIdResourceName) return false
            if (className != other.className) return false
            if (!boundsInScreen.contentEquals(other.boundsInScreen)) return false
            return children == other.children
        }

        override fun hashCode(): Int {
            var result = text?.hashCode() ?: 0
            result = 31 * result + (contentDescription?.hashCode() ?: 0)
            result = 31 * result + (viewIdResourceName?.hashCode() ?: 0)
            result = 31 * result + (className?.hashCode() ?: 0)
            result = 31 * result + boundsInScreen.contentHashCode()
            result = 31 * result + children.hashCode()
            return result
        }
    }

    /**
     * 获取当前活动窗口的根节点
     */
    fun getRootNode(): AccessibilityNodeInfo? {
        return getService()?.getRootInActiveWindowCompat()
    }

    /**
     * 获取完整的控件树结构（转换为纯数据类，避免内存泄漏）
     * @param maxDepth 最大遍历深度，默认20
     */
    fun getAccessibilityNodeTree(maxDepth: Int = 20): NodeInfo? {
        val rootNode = getRootNode() ?: return null
        return try {
            convertNodeToInfo(rootNode, maxDepth, 0)
        } finally {
            rootNode.recycle()
        }
    }

    /**
     * 将AccessibilityNodeInfo转换为NodeInfo数据类（递归）
     */
    private fun convertNodeToInfo(
        node: AccessibilityNodeInfo,
        maxDepth: Int,
        currentDepth: Int
    ): NodeInfo {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)

        val children = mutableListOf<NodeInfo>()
        if (currentDepth < maxDepth) {
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    children.add(convertNodeToInfo(child, maxDepth, currentDepth + 1))
                } finally {
                    child.recycle()
                }
            }
        }

        return NodeInfo(
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            viewIdResourceName = node.viewIdResourceName,
            className = node.className?.toString(),
            isClickable = node.isClickable,
            isScrollable = node.isScrollable,
            isEditable = node.isEditable,
            isCheckable = node.isCheckable,
            isChecked = node.isChecked,
            boundsInScreen = intArrayOf(rect.left, rect.top, rect.right, rect.bottom),
            childCount = node.childCount,
            children = children
        )
    }

    // ==================== 控件查找 ====================

    /**
     * 按文本查找控件
     * @param text 要查找的文本（完全匹配）
     * @param searchContentDesc 是否同时搜索contentDescription
     * @return 找到的控件列表（纯数据）
     */
    fun findNodesByText(
        text: String,
        searchContentDesc: Boolean = true
    ): List<NodeInfo> {
        val rootNode = getRootNode() ?: return emptyList()
        val results = mutableListOf<NodeInfo>()
        try {
            findNodesByTextRecursive(rootNode, text, searchContentDesc, results, 20, 0)
        } finally {
            rootNode.recycle()
        }
        return results
    }

    private fun findNodesByTextRecursive(
        node: AccessibilityNodeInfo,
        targetText: String,
        searchContentDesc: Boolean,
        results: MutableList<NodeInfo>,
        maxDepth: Int,
        currentDepth: Int
    ) {
        val nodeText = node.text?.toString()
        val nodeContentDesc = node.contentDescription?.toString()

        if (nodeText == targetText || (searchContentDesc && nodeContentDesc == targetText)) {
            results.add(convertNodeToInfo(node, 0, 0))
        }

        if (currentDepth < maxDepth) {
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    findNodesByTextRecursive(
                        child, targetText, searchContentDesc,
                        results, maxDepth, currentDepth + 1
                    )
                } finally {
                    child.recycle()
                }
            }
        }
    }

    /**
     * 按文本包含查找控件（模糊匹配）
     */
    fun findNodesByTextContains(
        keyword: String,
        searchContentDesc: Boolean = true
    ): List<NodeInfo> {
        val rootNode = getRootNode() ?: return emptyList()
        val results = mutableListOf<NodeInfo>()
        try {
            findNodesByTextContainsRecursive(
                rootNode, keyword, searchContentDesc, results, 20, 0
            )
        } finally {
            rootNode.recycle()
        }
        return results
    }

    private fun findNodesByTextContainsRecursive(
        node: AccessibilityNodeInfo,
        keyword: String,
        searchContentDesc: Boolean,
        results: MutableList<NodeInfo>,
        maxDepth: Int,
        currentDepth: Int
    ) {
        val nodeText = node.text?.toString() ?: ""
        val nodeContentDesc = node.contentDescription?.toString() ?: ""

        if (nodeText.contains(keyword, ignoreCase = true) ||
            (searchContentDesc && nodeContentDesc.contains(keyword, ignoreCase = true))
        ) {
            results.add(convertNodeToInfo(node, 0, 0))
        }

        if (currentDepth < maxDepth) {
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    findNodesByTextContainsRecursive(
                        child, keyword, searchContentDesc,
                        results, maxDepth, currentDepth + 1
                    )
                } finally {
                    child.recycle()
                }
            }
        }
    }

    /**
     * 按资源ID查找控件
     * @param viewId 资源ID（可以是完整路径如"com.android.settings:id/button"或仅ID名如"button"）
     */
    fun findNodesById(viewId: String): List<NodeInfo> {
        val rootNode = getRootNode() ?: return emptyList()
        val results = mutableListOf<NodeInfo>()
        try {
            findNodesByIdRecursive(rootNode, viewId, results, 20, 0)
        } finally {
            rootNode.recycle()
        }
        return results
    }

    private fun findNodesByIdRecursive(
        node: AccessibilityNodeInfo,
        targetId: String,
        results: MutableList<NodeInfo>,
        maxDepth: Int,
        currentDepth: Int
    ) {
        val resourceName = node.viewIdResourceName
        if (resourceName != null) {
            if (resourceName == targetId || resourceName.endsWith(":id/$targetId")) {
                results.add(convertNodeToInfo(node, 0, 0))
            }
        }

        if (currentDepth < maxDepth) {
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    findNodesByIdRecursive(
                        child, targetId, results, maxDepth, currentDepth + 1
                    )
                } finally {
                    child.recycle()
                }
            }
        }
    }

    /**
     * 按类名查找控件
     * @param className 类名（可以是完整类名或简单类名）
     */
    fun findNodesByClassName(className: String): List<NodeInfo> {
        val rootNode = getRootNode() ?: return emptyList()
        val results = mutableListOf<NodeInfo>()
        try {
            findNodesByClassNameRecursive(rootNode, className, results, 20, 0)
        } finally {
            rootNode.recycle()
        }
        return results
    }

    private fun findNodesByClassNameRecursive(
        node: AccessibilityNodeInfo,
        targetClassName: String,
        results: MutableList<NodeInfo>,
        maxDepth: Int,
        currentDepth: Int
    ) {
        val nodeClassName = node.className?.toString() ?: ""
        if (nodeClassName == targetClassName || nodeClassName.endsWith(".$targetClassName")) {
            results.add(convertNodeToInfo(node, 0, 0))
        }

        if (currentDepth < maxDepth) {
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    findNodesByClassNameRecursive(
                        child, targetClassName,
                        results, maxDepth, currentDepth + 1
                    )
                } finally {
                    child.recycle()
                }
            }
        }
    }

    // ==================== 控件动作执行 ====================

    /**
     * 对符合条件的第一个控件执行点击动作（按文本查找）
     * @param text 控件文本
     * @return 是否成功执行
     */
    fun clickByText(text: String): Boolean {
        return performActionOnFirstNode(
            findFirstNodeByText(text),
            AccessibilityNodeInfo.ACTION_CLICK
        )
    }

    /**
     * 对符合条件的第一个控件执行点击动作（按ID查找）
     * @param viewId 控件ID
     */
    fun clickById(viewId: String): Boolean {
        return performActionOnFirstNode(
            findFirstNodeById(viewId),
            AccessibilityNodeInfo.ACTION_CLICK
        )
    }

    /**
     * 对符合条件的第一个控件执行滚动动作
     * @param viewId 控件ID
     * @param forward true=向前滚动 false=向后滚动
     */
    fun scrollById(viewId: String, forward: Boolean = true): Boolean {
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return performActionOnFirstNode(findFirstNodeById(viewId), action)
    }

    /**
     * 对符合条件的第一个控件设置文本
     * @param viewId 控件ID
     * @param text 要设置的文本
     */
    fun setTextById(viewId: String, text: String): Boolean {
        val node = findFirstNodeById(viewId) ?: return false
        return try {
            val arguments = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        } finally {
            node.recycle()
        }
    }

    /**
     * 对符合条件的第一个控件设置文本（按文本查找目标控件）
     * @param hintText 目标控件的提示文本/描述
     * @param text 要设置的文本
     */
    fun setTextByHint(hintText: String, text: String): Boolean {
        val rootNode = getRootNode() ?: return false
        try {
            val target = findNodeByHintRecursive(rootNode, hintText, 20, 0)
            if (target != null) {
                try {
                    val arguments = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            text
                        )
                    }
                    return target.performAction(
                        AccessibilityNodeInfo.ACTION_SET_TEXT,
                        arguments
                    )
                } finally {
                    target.recycle()
                }
            }
        } finally {
            rootNode.recycle()
        }
        return false
    }

    private fun findNodeByHintRecursive(
        node: AccessibilityNodeInfo,
        hint: String,
        maxDepth: Int,
        currentDepth: Int
    ): AccessibilityNodeInfo? {
        val nodeHint = node.hintText?.toString()
        val nodeText = node.text?.toString()
        val nodeContentDesc = node.contentDescription?.toString()

        if ((nodeHint != null && nodeHint.contains(hint, ignoreCase = true)) ||
            (node.isEditable && (nodeText?.contains(hint, ignoreCase = true) == true ||
                    nodeContentDesc?.contains(hint, ignoreCase = true) == true))
        ) {
            return AccessibilityNodeInfo.obtain(node)
        }

        if (currentDepth < maxDepth) {
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    val found = findNodeByHintRecursive(
                        child, hint, maxDepth, currentDepth + 1
                    )
                    if (found != null) {
                        return found
                    }
                } finally {
                    child.recycle()
                }
            }
        }
        return null
    }

    /**
     * 查找第一个匹配文本的原始Node（返回值需要手动recycle）
     */
    private fun findFirstNodeByText(text: String): AccessibilityNodeInfo? {
        val rootNode = getRootNode() ?: return null
        try {
            return findFirstNodeByTextRecursive(rootNode, text, 20, 0)
        } finally {
            rootNode.recycle()
        }
    }

    private fun findFirstNodeByTextRecursive(
        node: AccessibilityNodeInfo,
        targetText: String,
        maxDepth: Int,
        currentDepth: Int
    ): AccessibilityNodeInfo? {
        val nodeText = node.text?.toString()
        val nodeContentDesc = node.contentDescription?.toString()
        if (nodeText == targetText || nodeContentDesc == targetText) {
            return AccessibilityNodeInfo.obtain(node)
        }
        if (currentDepth < maxDepth) {
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    val found = findFirstNodeByTextRecursive(
                        child, targetText, maxDepth, currentDepth + 1
                    )
                    if (found != null) return found
                } finally {
                    child.recycle()
                }
            }
        }
        return null
    }

    /**
     * 查找第一个匹配ID的原始Node（返回值需要手动recycle）
     */
    private fun findFirstNodeById(viewId: String): AccessibilityNodeInfo? {
        val rootNode = getRootNode() ?: return null
        try {
            return findFirstNodeByIdRecursive(rootNode, viewId, 20, 0)
        } finally {
            rootNode.recycle()
        }
    }

    private fun findFirstNodeByIdRecursive(
        node: AccessibilityNodeInfo,
        targetId: String,
        maxDepth: Int,
        currentDepth: Int
    ): AccessibilityNodeInfo? {
        val resourceName = node.viewIdResourceName
        if (resourceName != null &&
            (resourceName == targetId || resourceName.endsWith(":id/$targetId"))
        ) {
            return AccessibilityNodeInfo.obtain(node)
        }
        if (currentDepth < maxDepth) {
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    val found = findFirstNodeByIdRecursive(
                        child, targetId, maxDepth, currentDepth + 1
                    )
                    if (found != null) return found
                } finally {
                    child.recycle()
                }
            }
        }
        return null
    }

    /**
     * 对节点执行动作的辅助方法
     */
    private fun performActionOnFirstNode(
        node: AccessibilityNodeInfo?,
        action: Int
    ): Boolean {
        node ?: return false
        return try {
            node.performAction(action)
        } finally {
            node.recycle()
        }
    }

    // ==================== 事件监听 ====================

    /**
     * 无障碍事件监听器
     */
    interface AccessibilityEventListener {
        fun onAccessibilityEvent(event: AccessibilityEvent)
    }

    private val eventListeners = mutableSetOf<AccessibilityEventListener>()

    /**
     * 注册无障碍事件监听器
     */
    fun registerEventListener(listener: AccessibilityEventListener) {
        eventListeners.add(listener)
    }

    /**
     * 注销无障碍事件监听器
     */
    fun unregisterEventListener(listener: AccessibilityEventListener) {
        eventListeners.remove(listener)
    }

    /**
     * 分发事件给监听器（由Service内部调用）
     */
    internal fun dispatchAccessibilityEvent(event: AccessibilityEvent) {
        eventListeners.forEach { it.onAccessibilityEvent(event) }
    }

    // ==================== suspend 封装 + 超时机制 ====================

    /**
     * 手势suspend执行超时异常
     */
    class GestureTimeoutException(message: String = "手势执行超时") : Exception(message)

    /**
     * 无障碍服务未连接异常
     */
    class AccessibilityNotConnectedException : Exception("无障碍服务未连接，请先在系统设置中开启")

    /**
     * suspend版本：点击指定坐标（带超时）
     * @param x 屏幕X坐标
     * @param y 屏幕Y坐标
     * @param duration 按压持续时间（毫秒），默认50ms
     * @param timeoutMs 超时时间（毫秒），默认3000ms
     * @throws GestureTimeoutException 执行超时
     * @throws AccessibilityNotConnectedException 服务未连接
     */
    suspend fun clickSuspend(
        x: Float,
        y: Float,
        duration: Long = 50L,
        timeoutMs: Long = 3000L
    ): Boolean = withContext(Dispatchers.Default) {
        if (!isServiceConnected()) {
            throw AccessibilityNotConnectedException()
        }
        try {
            withTimeout(timeoutMs) {
                kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                    val submitted = click(x, y, duration,
                        object : GestureResultCallback {
                            override fun onSuccess() {
                                if (cont.isActive) cont.resumeWith(Result.success(true))
                            }

                            override fun onFailure(reason: String) {
                                if (cont.isActive) cont.resumeWith(Result.success(false))
                            }
                        })
                    if (!submitted) {
                        if (cont.isActive) cont.resumeWith(Result.success(false))
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw GestureTimeoutException("点击($x, $y) 执行超时(${timeoutMs}ms)")
        }
    }

    /**
     * suspend版本：长按指定坐标（带超时）
     */
    suspend fun longClickSuspend(
        x: Float,
        y: Float,
        duration: Long = 500L,
        timeoutMs: Long = 3000L
    ): Boolean = withContext(Dispatchers.Default) {
        if (!isServiceConnected()) {
            throw AccessibilityNotConnectedException()
        }
        try {
            withTimeout(timeoutMs) {
                kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                    val submitted = longClick(x, y, duration,
                        object : GestureResultCallback {
                            override fun onSuccess() {
                                if (cont.isActive) cont.resumeWith(Result.success(true))
                            }
                            override fun onFailure(reason: String) {
                                if (cont.isActive) cont.resumeWith(Result.success(false))
                            }
                        })
                    if (!submitted) {
                        if (cont.isActive) cont.resumeWith(Result.success(false))
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw GestureTimeoutException("长按($x, $y) 执行超时(${timeoutMs}ms)")
        }
    }

    /**
     * suspend版本：滑动手势（带超时）
     */
    suspend fun swipeSuspend(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        duration: Long = 300L,
        timeoutMs: Long = 3000L
    ): Boolean = withContext(Dispatchers.Default) {
        if (!isServiceConnected()) {
            throw AccessibilityNotConnectedException()
        }
        try {
            withTimeout(timeoutMs) {
                kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                    val submitted = swipe(startX, startY, endX, endY, duration,
                        object : GestureResultCallback {
                            override fun onSuccess() {
                                if (cont.isActive) cont.resumeWith(Result.success(true))
                            }
                            override fun onFailure(reason: String) {
                                if (cont.isActive) cont.resumeWith(Result.success(false))
                            }
                        })
                    if (!submitted) {
                        if (cont.isActive) cont.resumeWith(Result.success(false))
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw GestureTimeoutException("滑动($startX,$startY → $endX,$endY) 执行超时(${timeoutMs}ms)")
        }
    }

    /**
     * suspend版本：多点触控手势（带超时）
     */
    suspend fun multiTouchSuspend(
        strokes: List<Triple<Path, Long, Long>>,
        timeoutMs: Long = 5000L
    ): Boolean = withContext(Dispatchers.Default) {
        if (!isServiceConnected()) {
            throw AccessibilityNotConnectedException()
        }
        try {
            withTimeout(timeoutMs) {
                kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                    val submitted = multiTouch(strokes,
                        object : GestureResultCallback {
                            override fun onSuccess() {
                                if (cont.isActive) cont.resumeWith(Result.success(true))
                            }
                            override fun onFailure(reason: String) {
                                if (cont.isActive) cont.resumeWith(Result.success(false))
                            }
                        })
                    if (!submitted) {
                        if (cont.isActive) cont.resumeWith(Result.success(false))
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw GestureTimeoutException("多点触控执行超时(${timeoutMs}ms)")
        }
    }

    /**
     * suspend版本：文本输入（带超时）
     * @param text 要输入的文本
     * @param timeoutMs 超时时间（毫秒），默认2000ms
     */
    suspend fun inputTextSuspend(
        text: String,
        timeoutMs: Long = 2000L
    ): Boolean = withContext(Dispatchers.Default) {
        if (!isServiceConnected()) {
            throw AccessibilityNotConnectedException()
        }
        try {
            withTimeout(timeoutMs) {
                inputText(text)
            }
        } catch (e: TimeoutCancellationException) {
            throw GestureTimeoutException("文本输入执行超时(${timeoutMs}ms)")
        }
    }

    /**
     * suspend版本：读取完整控件树（带超时，防止遍历卡死）
     * @param maxDepth 最大遍历深度，默认20
     * @param timeoutMs 超时时间（毫秒），默认5000ms
     */
    suspend fun getAccessibilityNodeTreeSuspend(
        maxDepth: Int = 20,
        timeoutMs: Long = 5000L
    ): NodeInfo? = withContext(Dispatchers.Default) {
        if (!isServiceConnected()) {
            throw AccessibilityNotConnectedException()
        }
        try {
            withTimeout(timeoutMs) {
                getAccessibilityNodeTree(maxDepth)
            }
        } catch (e: TimeoutCancellationException) {
            throw GestureTimeoutException("控件树遍历超时(${timeoutMs}ms)")
        }
    }

    /**
     * suspend版本：按文本查找控件（带超时）
     */
    suspend fun findNodesByTextSuspend(
        text: String,
        searchContentDesc: Boolean = true,
        timeoutMs: Long = 3000L
    ): List<NodeInfo> = withContext(Dispatchers.Default) {
        if (!isServiceConnected()) {
            throw AccessibilityNotConnectedException()
        }
        try {
            withTimeout(timeoutMs) {
                findNodesByText(text, searchContentDesc)
            }
        } catch (e: TimeoutCancellationException) {
            throw GestureTimeoutException("按文本查找控件超时(${timeoutMs}ms)")
        }
    }

    /**
     * suspend版本：按ID查找控件（带超时）
     */
    suspend fun findNodesByIdSuspend(
        viewId: String,
        timeoutMs: Long = 3000L
    ): List<NodeInfo> = withContext(Dispatchers.Default) {
        if (!isServiceConnected()) {
            throw AccessibilityNotConnectedException()
        }
        try {
            withTimeout(timeoutMs) {
                findNodesById(viewId)
            }
        } catch (e: TimeoutCancellationException) {
            throw GestureTimeoutException("按ID查找控件超时(${timeoutMs}ms)")
        }
    }

    /**
     * suspend版本：按类名查找控件（带超时）
     */
    suspend fun findNodesByClassNameSuspend(
        className: String,
        timeoutMs: Long = 3000L
    ): List<NodeInfo> = withContext(Dispatchers.Default) {
        if (!isServiceConnected()) {
            throw AccessibilityNotConnectedException()
        }
        try {
            withTimeout(timeoutMs) {
                findNodesByClassName(className)
            }
        } catch (e: TimeoutCancellationException) {
            throw GestureTimeoutException("按类名查找控件超时(${timeoutMs}ms)")
        }
    }

    /**
     * suspend版本：执行安全的点击（先检查服务连接+超时）
     * 对 clickByText / clickById 也进行包装
     */
    suspend fun clickByTextSuspend(text: String, timeoutMs: Long = 2000L): Boolean =
        withContext(Dispatchers.Default) {
            if (!isServiceConnected()) throw AccessibilityNotConnectedException()
            withTimeout(timeoutMs) { clickByText(text) }
        }

    suspend fun clickByIdSuspend(viewId: String, timeoutMs: Long = 2000L): Boolean =
        withContext(Dispatchers.Default) {
            if (!isServiceConnected()) throw AccessibilityNotConnectedException()
            withTimeout(timeoutMs) { clickById(viewId) }
        }
}
