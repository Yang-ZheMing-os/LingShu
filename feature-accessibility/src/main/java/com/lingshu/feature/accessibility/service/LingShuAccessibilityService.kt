package com.lingshu.feature.accessibility.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.lingshu.core.common.log.LingShuLog

class LingShuAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AccessibilityService"

        @Volatile
        private var instance: LingShuAccessibilityService? = null

        fun getInstance(): LingShuAccessibilityService? = instance

        fun isServiceRunning(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        LingShuLog.i(TAG, "无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
        LingShuLog.w(TAG, "无障碍服务被中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        LingShuLog.i(TAG, "无障碍服务已销毁")
    }

    fun tap(x: Int, y: Int, callback: (Boolean) -> Unit) {
        try {
            val path = Path().apply {
                moveTo(x.toFloat(), y.toFloat())
            }
            val gestureBuilder = GestureDescription.Builder()
            gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            val result = dispatchGesture(
                gestureBuilder.build(),
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        callback(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        callback(false)
                    }
                },
                null
            )
            if (!result) {
                callback(false)
            }
        } catch (e: Exception) {
            LingShuLog.e(TAG, "点击失败: x=$x, y=$y", e)
            callback(false)
        }
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Int, callback: (Boolean) -> Unit) {
        try {
            val path = Path().apply {
                moveTo(x1.toFloat(), y1.toFloat())
                lineTo(x2.toFloat(), y2.toFloat())
            }
            val gestureBuilder = GestureDescription.Builder()
            gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, duration.toLong()))
            val result = dispatchGesture(
                gestureBuilder.build(),
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        callback(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        callback(false)
                    }
                },
                null
            )
            if (!result) {
                callback(false)
            }
        } catch (e: Exception) {
            LingShuLog.e(TAG, "滑动失败: ($x1,$y1) -> ($x2,$y2)", e)
            callback(false)
        }
    }

    fun pressBack(): Boolean {
        return try {
            performGlobalAction(GLOBAL_ACTION_BACK)
        } catch (e: Exception) {
            LingShuLog.e(TAG, "返回键失败", e)
            false
        }
    }

    fun pressHome(): Boolean {
        return try {
            performGlobalAction(GLOBAL_ACTION_HOME)
        } catch (e: Exception) {
            LingShuLog.e(TAG, "Home键失败", e)
            false
        }
    }

    fun getRootNode(): AccessibilityNodeInfo? {
        return try {
            rootInActiveWindow
        } catch (e: Exception) {
            LingShuLog.e(TAG, "获取根节点失败", e)
            null
        }
    }

    fun findNodeByText(text: String): AccessibilityNodeInfo? {
        val root = getRootNode() ?: return null
        return findNodeByTextRecursive(root, text)
    }

    private fun findNodeByTextRecursive(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.text?.toString() == text || node.contentDescription?.toString() == text) {
            return node
        }
        if (node.text?.contains(text, true) == true ||
            node.contentDescription?.contains(text, true) == true
        ) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByTextRecursive(child, text)
            if (found != null) {
                return found
            }
        }
        return null
    }

    fun findNodeById(id: String): AccessibilityNodeInfo? {
        val root = getRootNode() ?: return null
        return try {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            nodes.firstOrNull()
        } catch (e: Exception) {
            LingShuLog.e(TAG, "按ID查找节点失败: $id", e)
            null
        }
    }

    fun getAllScreenText(): String {
        val root = getRootNode() ?: return ""
        val stringBuilder = StringBuilder()
        collectTextRecursive(root, stringBuilder)
        return stringBuilder.toString().trim()
    }

    private fun collectTextRecursive(node: AccessibilityNodeInfo, builder: StringBuilder) {
        node.text?.let {
            if (it.isNotBlank()) {
                builder.append(it).append("\n")
            }
        }
        node.contentDescription?.let {
            if (it.isNotBlank()) {
                builder.append(it).append("\n")
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTextRecursive(child, builder)
        }
    }

    fun inputText(text: String): Boolean {
        val root = getRootNode() ?: return false
        val focusNode = findFocusedNode(root)
        return if (focusNode != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val arguments = android.os.Bundle()
                    arguments.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        text
                    )
                    focusNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                } else {
                    false
                }
            } catch (e: Exception) {
                LingShuLog.e(TAG, "输入文本失败", e)
                false
            }
        } else {
            LingShuLog.w(TAG, "未找到焦点输入框")
            false
        }
    }

    private fun findFocusedNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFocusedNode(child)
            if (found != null) {
                return found
            }
        }
        return null
    }
}
