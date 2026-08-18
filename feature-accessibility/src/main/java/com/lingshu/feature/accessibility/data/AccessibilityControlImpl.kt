package com.lingshu.feature.accessibility.data

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.lingshu.core.common.error.Result
import com.lingshu.core.common.error.ErrorCodes
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.feature.accessibility.domain.ControlInfo
import com.lingshu.feature.accessibility.domain.IAccessibilityControl
import com.lingshu.feature.accessibility.service.LingShuAccessibilityService
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import kotlin.coroutines.resume

class AccessibilityControlImpl @Inject constructor() : IAccessibilityControl {

    private companion object {
        private const val TAG = "AccessibilityControl"
        private const val DEFAULT_SWIPE_DURATION = 300
    }

    override suspend fun tap(x: Int, y: Int): Result<Unit> {
        LingShuLog.d(TAG, "执行点击: x=$x, y=$y")
        if (!isServiceRunning()) {
            return Result.Error(
                code = ErrorCodes.ACCESSIBILITY_DISABLED,
                message = ErrorCodes.getMessage(ErrorCodes.ACCESSIBILITY_DISABLED)
            )
        }
        return suspendCancellableCoroutine { continuation ->
            val service = LingShuAccessibilityService.getInstance()
            if (service == null) {
                continuation.resume(
                    Result.Error(
                        code = ErrorCodes.ACCESSIBILITY_DISABLED,
                        message = ErrorCodes.getMessage(ErrorCodes.ACCESSIBILITY_DISABLED)
                    )
                )
                return@suspendCancellableCoroutine
            }
            service.tap(x, y) { success ->
                if (success) {
                    LingShuLog.d(TAG, "点击成功: x=$x, y=$y")
                    continuation.resume(Result.Success(Unit))
                } else {
                    LingShuLog.e(TAG, "点击失败: x=$x, y=$y")
                    continuation.resume(
                        Result.Error(
                            code = ErrorCodes.UNKNOWN_ERROR,
                            message = "点击失败"
                        )
                    )
                }
            }
        }
    }

    override suspend fun tapByText(text: String): Result<Unit> {
        LingShuLog.d(TAG, "按文本点击: $text")
        if (!isServiceRunning()) {
            return Result.Error(
                code = ErrorCodes.ACCESSIBILITY_DISABLED,
                message = ErrorCodes.getMessage(ErrorCodes.ACCESSIBILITY_DISABLED)
            )
        }
        val service = LingShuAccessibilityService.getInstance() ?: return Result.Error(
            code = ErrorCodes.ACCESSIBILITY_DISABLED,
            message = ErrorCodes.getMessage(ErrorCodes.ACCESSIBILITY_DISABLED)
        )
        return try {
            val node = findNodeByTextPriority(service, text)
            if (node == null) {
                LingShuLog.w(TAG, "未找到控件: $text")
                return Result.Error(
                    code = ErrorCodes.UNKNOWN_ERROR,
                    message = "未找到控件: $text"
                )
            }
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val centerX = rect.centerX()
            val centerY = rect.centerY()
            LingShuLog.d(TAG, "找到控件，点击位置: ($centerX, $centerY)")
            tap(centerX, centerY)
        } catch (e: Exception) {
            LingShuLog.e(TAG, "按文本点击失败: $text", e)
            Result.Error(code = ErrorCodes.UNKNOWN_ERROR, message = e.message ?: "按文本点击失败", cause = e)
        }
    }

    override suspend fun swipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        duration: Int
    ): Result<Unit> {
        LingShuLog.d(TAG, "执行滑动: ($x1,$y1) -> ($x2,$y2), duration=$duration")
        if (!isServiceRunning()) {
            return Result.Error(
                code = ErrorCodes.ACCESSIBILITY_DISABLED,
                message = ErrorCodes.getMessage(ErrorCodes.ACCESSIBILITY_DISABLED)
            )
        }
        return suspendCancellableCoroutine { continuation ->
            val service = LingShuAccessibilityService.getInstance()
            if (service == null) {
                continuation.resume(
                    Result.Error(
                        code = ErrorCodes.ACCESSIBILITY_DISABLED,
                        message = ErrorCodes.getMessage(ErrorCodes.ACCESSIBILITY_DISABLED)
                    )
                )
                return@suspendCancellableCoroutine
            }
            val swipeDuration = if (duration > 0) duration else DEFAULT_SWIPE_DURATION
            service.swipe(x1, y1, x2, y2, swipeDuration) { success ->
                if (success) {
                    LingShuLog.d(TAG, "滑动成功: ($x1,$y1) -> ($x2,$y2)")
                    continuation.resume(Result.Success(Unit))
                } else {
                    LingShuLog.e(TAG, "滑动失败: ($x1,$y1) -> ($x2,$y2)")
                    continuation.resume(
                        Result.Error(
                            code = ErrorCodes.UNKNOWN_ERROR,
                            message = "滑动失败"
                        )
                    )
                }
            }
        }
    }

    override suspend fun inputText(text: String): Result<Unit> {
        LingShuLog.d(TAG, "输入文本: $text")
        if (!isServiceRunning()) {
            return Result.Error(
                code = ErrorCodes.ACCESSIBILITY_DISABLED,
                message = ErrorCodes.getMessage(ErrorCodes.ACCESSIBILITY_DISABLED)
            )
        }
        val service = LingShuAccessibilityService.getInstance() ?: return Result.Error(
            code = ErrorCodes.ACCESSIBILITY_DISABLED,
            message = ErrorCodes.getMessage(ErrorCodes.ACCESSIBILITY_DISABLED)
        )
        return try {
            val success = service.inputText(text)
            if (success) {
                LingShuLog.d(TAG, "输入文本成功")
                Result.Success(Unit)
            } else {
                LingShuLog.e(TAG, "输入文本失败")
                Result.Error(
                    code = ErrorCodes.UNKNOWN_ERROR,
                    message = "输入文本失败"
                )
            }
        } catch (e: Exception) {
            LingShuLog.e(TAG, "输入文本异常", e)
            Result.Error(code = ErrorCodes.UNKNOWN_ERROR, message = e.message ?: "输入文本异常", cause = e)
        }
    }

    override suspend fun pressBack(): Result<Unit> {
        LingShuLog.d(TAG, "按返回键")
        if (!isServiceRunning()) {
            return Result.Error(
                code = ErrorCodes.ACCESSIBILITY_DISABLED,
                message = ErrorCodes.getMessage(ErrorCodes.ACCESSIBILITY_DISABLED)
            )
        }
        val service = LingShuAccessibilityService.getInstance() ?: return Result.Error(
            code = ErrorCodes.ACCESSIBILITY_DISABLED,
            message = ErrorCodes.getMessage(ErrorCodes.ACCESSIBILITY_DISABLED)
        )
        return try {
            val success = service.pressBack()
            if (success) {
                LingShuLog.d(TAG, "返回键成功")
                Result.Success(Unit)
            } else {
                LingShuLog.e(TAG, "返回键失败")
                Result.Error(
                    code = ErrorCodes.UNKNOWN_ERROR,
                    message = "返回键失败"
                )
            }
        } catch (e: Exception) {
            LingShuLog.e(TAG, "返回键异常", e)
            Result.Error(code = ErrorCodes.UNKNOWN_ERROR, message = e.message ?: "返回键异常", cause = e)
        }
    }

    override suspend fun pressHome(): Result<Unit> {
        LingShuLog.d(TAG, "按Home键")
        if (!isServiceRunning()) {
            return Result.Error(
                code = ErrorCodes.ACCESSIBILITY_DISABLED,
                message = ErrorCodes.getMessage(ErrorCodes.ACCESSIBILITY_DISABLED)
            )
        }
        val service = LingShuAccessibilityService.getInstance() ?: return Result.Error(
            code = ErrorCodes.ACCESSIBILITY_DISABLED,
            message = ErrorCodes.getMessage(ErrorCodes.ACCESSIBILITY_DISABLED)
        )
        return try {
            val success = service.pressHome()
            if (success) {
                LingShuLog.d(TAG, "Home键成功")
                Result.Success(Unit)
            } else {
                LingShuLog.e(TAG, "Home键失败")
                Result.Error(
                    code = ErrorCodes.UNKNOWN_ERROR,
                    message = "Home键失败"
                )
            }
        } catch (e: Exception) {
            LingShuLog.e(TAG, "Home键异常", e)
            Result.Error(code = ErrorCodes.UNKNOWN_ERROR, message = e.message ?: "Home键异常", cause = e)
        }
    }

    override suspend fun getScreenText(): Result<String> {
        LingShuLog.d(TAG, "获取屏幕文本")
        if (!isServiceRunning()) {
            return Result.Error(
                code = ErrorCodes.ACCESSIBILITY_DISABLED,
                message = ErrorCodes.getMessage(ErrorCodes.ACCESSIBILITY_DISABLED)
            )
        }
        val service = LingShuAccessibilityService.getInstance() ?: return Result.Error(
            code = ErrorCodes.ACCESSIBILITY_DISABLED,
            message = ErrorCodes.getMessage(ErrorCodes.ACCESSIBILITY_DISABLED)
        )
        return try {
            val text = service.getAllScreenText()
            LingShuLog.d(TAG, "获取屏幕文本成功，长度: ${text.length}")
            Result.Success(text)
        } catch (e: Exception) {
            LingShuLog.e(TAG, "获取屏幕文本异常", e)
            Result.Error(code = ErrorCodes.UNKNOWN_ERROR, message = e.message ?: "获取屏幕文本异常", cause = e)
        }
    }

    override suspend fun findControlByText(text: String): Result<ControlInfo?> {
        LingShuLog.d(TAG, "按文本查找控件: $text")
        if (!isServiceRunning()) {
            return Result.Error(
                code = ErrorCodes.ACCESSIBILITY_DISABLED,
                message = ErrorCodes.getMessage(ErrorCodes.ACCESSIBILITY_DISABLED)
            )
        }
        val service = LingShuAccessibilityService.getInstance() ?: return Result.Error(
            code = ErrorCodes.ACCESSIBILITY_DISABLED,
            message = ErrorCodes.getMessage(ErrorCodes.ACCESSIBILITY_DISABLED)
        )
        return try {
            val node = findNodeByTextPriority(service, text)
            val controlInfo = node?.let { nodeToControlInfo(it) }
            if (controlInfo != null) {
                LingShuLog.d(TAG, "找到控件: $text")
            } else {
                LingShuLog.w(TAG, "未找到控件: $text")
            }
            Result.Success(controlInfo)
        } catch (e: Exception) {
            LingShuLog.e(TAG, "按文本查找控件异常: $text", e)
            Result.Error(code = ErrorCodes.UNKNOWN_ERROR, message = e.message ?: "按文本查找控件异常", cause = e)
        }
    }

    override suspend fun findControlById(id: String): Result<ControlInfo?> {
        LingShuLog.d(TAG, "按ID查找控件: $id")
        if (!isServiceRunning()) {
            return Result.Error(
                code = ErrorCodes.ACCESSIBILITY_DISABLED,
                message = ErrorCodes.getMessage(ErrorCodes.ACCESSIBILITY_DISABLED)
            )
        }
        val service = LingShuAccessibilityService.getInstance() ?: return Result.Error(
            code = ErrorCodes.ACCESSIBILITY_DISABLED,
            message = ErrorCodes.getMessage(ErrorCodes.ACCESSIBILITY_DISABLED)
        )
        return try {
            val node = service.findNodeById(id)
            val controlInfo = node?.let { nodeToControlInfo(it) }
            if (controlInfo != null) {
                LingShuLog.d(TAG, "找到控件: $id")
            } else {
                LingShuLog.w(TAG, "未找到控件: $id")
            }
            Result.Success(controlInfo)
        } catch (e: Exception) {
            LingShuLog.e(TAG, "按ID查找控件异常: $id", e)
            Result.Error(code = ErrorCodes.UNKNOWN_ERROR, message = e.message ?: "按ID查找控件异常", cause = e)
        }
    }

    override suspend fun longPress(x: Float, y: Float, durationMs: Long): Result<Unit> {
        LingShuLog.d(TAG, "execute long press: x=$x, y=$y, duration=$durationMs")
        if (!isServiceRunning()) {
            return Result.Error(
                code = ErrorCodes.ACCESSIBILITY_DISABLED,
                message = ErrorCodes.getMessage(ErrorCodes.ACCESSIBILITY_DISABLED)
            )
        }
        val service = LingShuAccessibilityService.getInstance() ?: return Result.Error(
            code = ErrorCodes.ACCESSIBILITY_DISABLED,
            message = ErrorCodes.getMessage(ErrorCodes.ACCESSIBILITY_DISABLED)
        )
        return try {
            val path = android.graphics.Path().apply { moveTo(x, y) }
            val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(
                path, 0, durationMs
            )
            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(stroke)
                .build()

            val dispatched = service.dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: android.accessibilityservice.GestureDescription?) {}
                override fun onCancelled(g: android.accessibilityservice.GestureDescription?) {}
            }, null)

            if (dispatched) {
                LingShuLog.d(TAG, "long press dispatched: x=$x, y=$y")
                Result.Success(Unit)
            } else {
                LingShuLog.e(TAG, "long press dispatch failed: x=$x, y=$y")
                Result.Error(
                    code = ErrorCodes.UNKNOWN_ERROR,
                    message = "long press dispatch failed"
                )
            }
        } catch (e: Exception) {
            LingShuLog.e(TAG, "long press failed: x=$x, y=$y", e)
            Result.Error(code = ErrorCodes.UNKNOWN_ERROR, message = "long press failed: ${e.message}", cause = e)
        }
    }

    override suspend fun takeScreenshot(): Result<Unit> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return Result.Error(
                code = ErrorCodes.UNKNOWN_ERROR,
                message = "截屏需要 Android 9 及以上系统"
            )
        }
        if (!isServiceRunning()) {
            return Result.Error(
                code = ErrorCodes.ACCESSIBILITY_DISABLED,
                message = ErrorCodes.getMessage(ErrorCodes.ACCESSIBILITY_DISABLED)
            )
        }
        val service = LingShuAccessibilityService.getInstance() ?: return Result.Error(
            code = ErrorCodes.ACCESSIBILITY_DISABLED,
            message = ErrorCodes.getMessage(ErrorCodes.ACCESSIBILITY_DISABLED)
        )
        return suspendCancellableCoroutine { continuation ->
            service.takeScreenshot { success ->
                if (success) {
                    LingShuLog.d(TAG, "截屏成功（系统已保存到相册）")
                    continuation.resume(Result.Success(Unit))
                } else {
                    LingShuLog.e(TAG, "截屏失败")
                    continuation.resume(
                        Result.Error(
                            code = ErrorCodes.UNKNOWN_ERROR,
                            message = "截屏失败，请确保已开启无障碍服务且系统为 Android 9+"
                        )
                    )
                }
            }
        }
    }

    override suspend fun isServiceRunning(): Boolean {
        return LingShuAccessibilityService.isServiceRunning()
    }

    private fun findNodeByTextPriority(
        service: LingShuAccessibilityService,
        text: String
    ): AccessibilityNodeInfo? {
        val root = service.getRootNode() ?: return null

        var node = findNodeByExactText(root, text)
        if (node != null) {
            LingShuLog.d(TAG, "精确匹配文本: $text")
            return node
        }

        node = findNodeByContainText(root, text)
        if (node != null) {
            LingShuLog.d(TAG, "包含匹配文本: $text")
            return node
        }

        node = service.findNodeById(text)
        if (node != null) {
            LingShuLog.d(TAG, "ID匹配: $text")
            return node
        }

        node = findNodeByClassName(root, text)
        if (node != null) {
            LingShuLog.d(TAG, "类名匹配: $text")
            return node
        }

        return null
    }

    private fun findNodeByExactText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nodeText = node.text?.toString()
        val nodeContentDesc = node.contentDescription?.toString()
        if (nodeText == text || nodeContentDesc == text) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByExactText(child, text)
            if (found != null) {
                return found
            }
        }
        return null
    }

    private fun findNodeByContainText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nodeText = node.text?.toString()
        val nodeContentDesc = node.contentDescription?.toString()
        if (nodeText?.contains(text, true) == true ||
            nodeContentDesc?.contains(text, true) == true
        ) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByContainText(child, text)
            if (found != null) {
                return found
            }
        }
        return null
    }

    private fun findNodeByClassName(node: AccessibilityNodeInfo, className: String): AccessibilityNodeInfo? {
        if (node.className?.contains(className, true) == true) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByClassName(child, className)
            if (found != null) {
                return found
            }
        }
        return null
    }

    private fun nodeToControlInfo(node: AccessibilityNodeInfo): ControlInfo {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return ControlInfo(
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            className = node.className?.toString(),
            bounds = bounds,
            isClickable = node.isClickable
        )
    }
}
