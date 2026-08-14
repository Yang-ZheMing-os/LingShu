package com.lingshu.feature.control.domain

import android.content.Context
import android.media.AudioManager
import com.lingshu.core.common.error.ErrorCodes
import com.lingshu.core.common.error.Result
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.feature.accessibility.domain.IAccessibilityControl
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 指令执行器：把 [Command] 映射到 [ISystemControl] / [IAccessibilityControl] 的具体调用。
 *
 * 抽取自 ControlViewModel，供 ControlViewModel 和 AiReplyToControlBridge 复用，
 * 避免在两处重复 SystemAction->setXxx 的映射逻辑。
 *
 * App 内部操作（[Command.AppAction]）通过无障碍服务执行：打开 App -> 等待就绪 -> 界面自动化。
 */
@Singleton
class CommandExecutor @Inject constructor(
    private val systemControl: ISystemControl,
    private val accessibilityControl: IAccessibilityControl,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "CommandExecutor"
        /** 打开 App 后等待界面就绪的延迟（毫秒） */
        private const val APP_OPEN_DELAY_MS = 2000L
        /** 单步界面操作之间的延迟（毫秒） */
        private const val STEP_DELAY_MS = 800L
        /** 滚动一屏的手势时长（毫秒） */
        private const val DEFAULT_SCROLL_DURATION = 300
        /** 滚动手势在屏幕边缘的内边距比例（避免触到系统手势区） */
        private const val SCROLL_EDGE_MARGIN_RATIO = 0.2f
    }

    /**
     * 根据包名获取应用在桌面上显示的名称（label）。找不到或异常时返回 null。
     * 用于把 OpenApp 成功后的回复从包名还原成用户熟悉的应用名。
     */
    fun appDisplayName(packageName: String): String? {
        if (packageName.isBlank()) return null
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo)?.toString()?.trim()?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun execute(command: Command): Result<Unit> {
        return when (command) {
            is Command.SystemControl -> executeSystemControl(command.action)
            is Command.OpenApp -> systemControl.openApp(command.packageName)
            is Command.CloseApp -> systemControl.closeApp(command.appName)
            Command.Screenshot -> systemControl.takeScreenshot()
            is Command.Navigate -> systemControl.navigateToMap(command.destination)
            Command.OpenTakeout -> systemControl.openTakeout()
            is Command.AppAction -> executeAppAction(command)
            // UI 自动化指令：直接委托无障碍服务，未启用时由各方法返回 ACCESSIBILITY_DISABLED
            is Command.UiTap -> accessibilityControl.tap(command.x, command.y)
            is Command.UiTapText -> accessibilityControl.tapByText(command.text)
            is Command.UiSwipe -> accessibilityControl.swipe(
                command.x1, command.y1, command.x2, command.y2, command.duration
            )
            is Command.UiScroll -> executeScroll(command.direction)
            is Command.UiInputText -> accessibilityControl.inputText(command.text)
            Command.UiPressBack -> accessibilityControl.pressBack()
            Command.UiPressHome -> accessibilityControl.pressHome()
            is Command.UiLongPress -> accessibilityControl.longPress(
                command.x, command.y, command.durationMs
            )
            is Command.Unknown -> Result.error(
                code = "UNKNOWN_COMMAND",
                message = "无法识别的指令: ${command.input}"
            )
        }
    }

    private suspend fun executeSystemControl(action: SystemAction): Result<Unit> {
        LingShuLog.i(TAG, "executeSystemControl: action=$action")
        return when (action) {
            SystemAction.WIFI_ON -> systemControl.setWifi(true)
            SystemAction.WIFI_OFF -> systemControl.setWifi(false)
            SystemAction.BLUETOOTH_ON -> systemControl.setBluetooth(true)
            SystemAction.BLUETOOTH_OFF -> systemControl.setBluetooth(false)
            SystemAction.FLASHLIGHT_ON -> systemControl.setFlashlight(true)
            SystemAction.FLASHLIGHT_OFF -> systemControl.setFlashlight(false)
            SystemAction.BRIGHTNESS_UP -> adjustBrightness(delta = 20)
            SystemAction.BRIGHTNESS_DOWN -> adjustBrightness(delta = -20)
            SystemAction.VOLUME_UP -> adjustVolume(delta = 20)
            SystemAction.VOLUME_DOWN -> adjustVolume(delta = -20)
            SystemAction.VOLUME_MUTE -> systemControl.setVolume(0)
            SystemAction.VOLUME_50 -> systemControl.setVolume(50)
            SystemAction.AUTO_ROTATE_ON -> systemControl.setAutoRotate(true)
            SystemAction.AUTO_ROTATE_OFF -> systemControl.setAutoRotate(false)
        }
    }

    /**
     * App 内部自动化操作：打开 App -> 等待就绪 -> 通过无障碍服务执行界面操作。
     *
     * 无障碍服务未启用时返回 [ErrorCodes.ACCESSIBILITY_DISABLED]，所有调用均做容错。
     */
    private suspend fun executeAppAction(command: Command.AppAction): Result<Unit> {
        val packageName = systemControl.getPackageNameByAppName(command.appName)
        if (packageName.isEmpty()) {
            LingShuLog.w(TAG, "未找到 App 包名: ${command.appName}")
            return Result.error(
                code = ErrorCodes.UNKNOWN_ERROR,
                message = "未找到应用: ${command.appName}"
            )
        }

        // 1. 打开目标 App
        val openResult = systemControl.openApp(packageName)
        if (openResult is Result.Error) {
            LingShuLog.e(TAG, "打开 App 失败: ${command.appName}", openResult.cause)
            return openResult
        }

        // 2. 等待 App 界面就绪
        delay(APP_OPEN_DELAY_MS)

        // 3. 检查无障碍服务是否可用
        if (!accessibilityControl.isServiceRunning()) {
            LingShuLog.w(TAG, "无障碍服务未启用，无法执行 App 内操作: ${command.appName}")
            return Result.error(
                code = ErrorCodes.ACCESSIBILITY_DISABLED,
                message = ErrorCodes.getMessage(ErrorCodes.ACCESSIBILITY_DISABLED)
            )
        }

        // 4. 按 action 分发界面操作
        return try {
            when (command.action) {
                "send_message" -> executeSendMessage(command)
                else -> {
                    LingShuLog.w(TAG, "未支持的 AppAction: ${command.action}")
                    Result.error(
                        code = ErrorCodes.UNKNOWN_ERROR,
                        message = "暂不支持的操作: ${command.action}"
                    )
                }
            }
        } catch (e: Exception) {
            LingShuLog.e(TAG, "AppAction 执行异常: ${command.action}", e)
            Result.error(
                code = ErrorCodes.UNKNOWN_ERROR,
                message = "App 内操作失败: ${e.message}",
                cause = e
            )
        }
    }

    /** 在 IM 类 App 中发送消息：点击联系人 -> 输入消息 -> 点击发送 */
    private suspend fun executeSendMessage(command: Command.AppAction): Result<Unit> {
        val contact = command.params["contact"].orEmpty()
        val message = command.params["message"].orEmpty()

        if (contact.isNotEmpty()) {
            // 点击联系人进入聊天
            val tapContact = accessibilityControl.tapByText(contact)
            if (tapContact is Result.Error) {
                LingShuLog.w(TAG, "未找到联系人控件: $contact")
                return tapContact
            }
            delay(STEP_DELAY_MS)
        }

        if (message.isNotEmpty()) {
            // 输入消息内容
            val inputResult = accessibilityControl.inputText(message)
            if (inputResult is Result.Error) {
                LingShuLog.w(TAG, "输入消息失败: $message")
                return inputResult
            }
            delay(STEP_DELAY_MS)

            // 点击发送按钮
            val sendResult = accessibilityControl.tapByText("发送")
            if (sendResult is Result.Error) {
                LingShuLog.w(TAG, "未找到发送按钮")
                return sendResult
            }
        }

        LingShuLog.i(TAG, "发送消息流程完成: contact=$contact, message=$message")
        return Result.success(Unit)
    }

    /**
     * 按方向滚动一屏：基于屏幕尺寸计算滑动起止坐标。
     *
     * - UP：手指由下向上滑（内容上移，看下方内容）
     * - DOWN：手指由上向下滑（内容下移，看上方内容）
     * - LEFT/RIGHT：横向滑动
     *
     * 无障碍服务未启用时返回 [ErrorCodes.ACCESSIBILITY_DISABLED]。
     */
    private suspend fun executeScroll(direction: ScrollDirection): Result<Unit> {
        if (!accessibilityControl.isServiceRunning()) {
            return Result.error(
                code = ErrorCodes.ACCESSIBILITY_DISABLED,
                message = ErrorCodes.getMessage(ErrorCodes.ACCESSIBILITY_DISABLED)
            )
        }
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val centerX = width / 2
        val centerY = height / 2
        val margin = SCROLL_EDGE_MARGIN_RATIO
        return when (direction) {
            ScrollDirection.UP -> accessibilityControl.swipe(
                centerX, (height * (1 - margin)).toInt(),
                centerX, (height * margin).toInt(),
                DEFAULT_SCROLL_DURATION
            )
            ScrollDirection.DOWN -> accessibilityControl.swipe(
                centerX, (height * margin).toInt(),
                centerX, (height * (1 - margin)).toInt(),
                DEFAULT_SCROLL_DURATION
            )
            ScrollDirection.LEFT -> accessibilityControl.swipe(
                (width * (1 - margin)).toInt(), centerY,
                (width * margin).toInt(), centerY,
                DEFAULT_SCROLL_DURATION
            )
            ScrollDirection.RIGHT -> accessibilityControl.swipe(
                (width * margin).toInt(), centerY,
                (width * (1 - margin)).toInt(), centerY,
                DEFAULT_SCROLL_DURATION
            )
        }
    }

    private suspend fun adjustBrightness(delta: Int): Result<Unit> {
        val current = getCurrentBrightness()
        val target = (current + delta).coerceIn(0, 100)
        return systemControl.setBrightness(target)
    }

    private suspend fun adjustVolume(delta: Int): Result<Unit> {
        val current = getCurrentVolume()
        val target = (current + delta).coerceIn(0, 100)
        return systemControl.setVolume(target)
    }

    private fun getCurrentBrightness(): Int {
        return try {
            val brightness = android.provider.Settings.System.getInt(
                context.contentResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS
            )
            (brightness / 255f * 100).toInt()
        } catch (e: Exception) {
            LingShuLog.w(TAG, "获取当前亮度失败", e)
            50
        }
    }

    private fun getCurrentVolume(): Int {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            ((audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max) * 100).toInt()
        } catch (e: Exception) {
            LingShuLog.w(TAG, "获取当前音量失败", e)
            50
        }
    }
}
