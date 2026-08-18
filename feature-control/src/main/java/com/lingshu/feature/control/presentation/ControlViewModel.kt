package com.lingshu.feature.control.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lingshu.core.common.state.UiState
import com.lingshu.core.common.error.ErrorCodes
import com.lingshu.core.common.error.Result
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.feature.control.domain.Command
import com.lingshu.feature.control.domain.CommandExecutor
import com.lingshu.feature.control.domain.ICommandParser
import com.lingshu.feature.control.domain.ISystemControl
import com.lingshu.feature.control.domain.SystemAction
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ControlViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val systemControl: ISystemControl,
    private val commandParser: ICommandParser,
    private val commandExecutor: CommandExecutor
) : ViewModel() {

    private val _controlState = MutableStateFlow<UiState<Unit>>(UiState.Idle)
    val controlState: StateFlow<UiState<Unit>> = _controlState.asStateFlow()

    private val _currentCommand = MutableStateFlow<Command?>(null)
    val currentCommand: StateFlow<Command?> = _currentCommand.asStateFlow()

    fun executeCommand(command: Command) {
        _currentCommand.value = command
        viewModelScope.launch {
            _controlState.value = UiState.Loading
            when (val result = executeCommandInternal(command)) {
                is Result.Success -> {
                    _controlState.value = UiState.Success(Unit)
                    LingShuLog.d("ControlViewModel", "指令执行成功: $command")
                }
                is Result.Error -> {
                    _controlState.value = UiState.Error(
                        code = result.code,
                        message = result.message
                    )
                    LingShuLog.e("ControlViewModel", "指令执行失败: ${result.code} - ${result.message}", result.cause)
                }
            }
        }
    }

    fun parseAndExecute(input: String) {
        val command = commandParser.parse(input)
        executeCommand(command)
    }

    private suspend fun executeCommandInternal(command: Command): Result<Unit> {
        return when (command) {
            is Command.SystemControl -> executeSystemControl(command.action)
            is Command.OpenApp -> systemControl.openApp(command.packageName)
            is Command.CloseApp -> systemControl.closeApp(command.appName)
            Command.Screenshot -> systemControl.takeScreenshot()
            is Command.Navigate -> systemControl.navigateToMap(command.destination)
            Command.OpenTakeout -> systemControl.openTakeout()
            is Command.WebSearch -> systemControl.webSearch(command.query)
            Command.PlayMusic -> systemControl.playMusic()
            is Command.SetAlarm -> systemControl.setAlarm(command.hour, command.minute, command.label)
            Command.OpenCamera -> systemControl.openCamera()
            is Command.MakeCall -> systemControl.makeCall(command.phoneNumberOrContact)
            is Command.SendSms -> systemControl.sendSms(command.phoneNumberOrContact, command.message)
            // ---------- 三大复合场景 ----------
            is Command.OrderTakeout -> systemControl.orderTakeout(
                foodKeyword = command.foodKeyword,
                restaurant = command.restaurant,
                addressHint = command.addressHint
            )
            is Command.SendChatMessage -> systemControl.sendChatMessage(
                contactNameOrPhone = command.contactNameOrPhone,
                message = command.message,
                channel = command.channel
            )
            is Command.CallRide -> systemControl.callRide(
                destination = command.destination,
                carTypePref = command.carTypePref
            )
            // UI 自动化指令统一委托 CommandExecutor（复用无障碍服务调用逻辑）
            is Command.UiTap,
            is Command.UiTapText,
            is Command.UiSwipe,
            is Command.UiScroll,
            is Command.UiInputText,
            Command.UiPressBack,
            Command.UiPressHome,
            is Command.UiLongPress -> commandExecutor.execute(command)
            // App 内部操作依赖无障碍编排，仅在 AI 指令链路（CommandExecutor）中执行
            is Command.AppAction -> Result.error(
                code = ErrorCodes.UNKNOWN_ERROR,
                message = "App 内部操作请通过 AI 对话触发"
            )
            is Command.Unknown -> Result.error(
                code = ErrorCodes.UNKNOWN_ERROR,
                message = "无法识别的指令: ${command.input}"
            )
        }
    }

    private suspend fun executeSystemControl(action: SystemAction): Result<Unit> {
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
            LingShuLog.w("ControlViewModel", "获取当前亮度失败", e)
            50
        }
    }

    private fun getCurrentVolume(): Int {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val currentVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            (currentVolume / maxVolume.toFloat() * 100).toInt()
        } catch (e: Exception) {
            LingShuLog.w("ControlViewModel", "获取当前音量失败", e)
            50
        }
    }

    fun resetState() {
        _controlState.value = UiState.Idle
    }
}
