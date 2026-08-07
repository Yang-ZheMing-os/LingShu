package com.lingshu.agent.feature.floating

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lingshu.agent.core.data.AppSettingsDataStore
import com.lingshu.agent.feature.floating.services.FloatingBubbleService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 悬浮窗 UI ViewModel
 *
 * 职责：
 * 1. 封装悬浮窗设置页（设置气泡大小、透明度、是否开机自启、权限状态等）的UI状态
 * 2. 管理 FloatingBubbleService 的绑定/解绑
 * 3. 与 AppSettingsDataStore 交互：保存用户偏好（大小、透明度、自启开关）
 * 4. 暴露权限检查方法给 Compose UI 调用
 *
 * 与 FloatingBubbleManager 的区别：
 * - FloatingBubbleManager: 直接操作WindowManager的「实现层」，由Service持有
 * - FloatingViewModel: 面向「设置页UI」的状态层，不直接持有Window对象
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FloatingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: AppSettingsDataStore,
    private val bubbleManager: FloatingBubbleManager
) : ViewModel() {

    // ==================== UI 状态 ====================

    data class FloatingSettingsUi(
        val enabled: Boolean = false,
        val hasOverlayPermission: Boolean = false,
        val bubbleSizeDp: Int = FloatingBubbleManager.DEFAULT_BUBBLE_SIZE_DP,
        val bubbleAlpha: Float = FloatingBubbleManager.DEFAULT_ALPHA,
        val autoStartOnBoot: Boolean = false,
        val isServiceRunning: Boolean = false,
        val currentState: FloatingBubbleState = FloatingBubbleState.STANDBY,
        val isBubbleVisible: Boolean = false,
        val isChatVisible: Boolean = false
    )

    private val _hasOverlayPermission = MutableStateFlow(bubbleManager.canDrawOverlays())
    private val _isServiceRunning = MutableStateFlow(false)

    /** 主UI状态：组合 DataStore 设置 + 实时 Manager 状态 + 权限状态 */
    val uiState: StateFlow<FloatingSettingsUi> = combine(
        combine(
            dataStore.isFloatingEnabledFlow,
            dataStore.floatingBubbleSizeFlow,
            dataStore.floatingBubbleAlphaFlow,
            dataStore.isFloatingAutoStartFlow,
            bubbleManager.bubbleState
        ) { enabled, sizeDp, alpha, autoStart, state ->
            CombinedSettings(enabled, sizeDp, alpha, autoStart, state)
        },
        bubbleManager.isVisible,
        bubbleManager.isChatVisible,
        _hasOverlayPermission,
        _isServiceRunning
    ) { settings, visible, chatVisible, perm, service ->
        FloatingSettingsUi(
            enabled = settings.enabled,
            hasOverlayPermission = perm,
            bubbleSizeDp = settings.sizeDp,
            bubbleAlpha = settings.alpha,
            autoStartOnBoot = settings.autoStart,
            isServiceRunning = service,
            currentState = settings.state,
            isBubbleVisible = visible,
            isChatVisible = chatVisible
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = FloatingSettingsUi()
    )

    // ==================== Service 绑定 ====================
    private var boundService: FloatingBubbleService? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? FloatingBubbleService.LocalBinder
            boundService = binder?.getService()
            _isServiceRunning.value = true
            applySettingsToRunningBubble()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            boundService = null
            _isServiceRunning.value = false
        }
    }

    private var isBound = false

    init {
        viewModelScope.launch {
            applySettingsToRunningBubble()
        }
    }

    // ==================== 对外操作 ====================

    /**
     * UI调用：刷新权限状态（设置页 onResume 时调用）
     */
    fun refreshPermissionState() {
        _hasOverlayPermission.value = bubbleManager.canDrawOverlays()
        _isServiceRunning.value = isServiceActuallyRunning()
    }

    /**
     * 请求悬浮窗权限
     */
    suspend fun requestOverlayPermission(activity: Activity): Boolean {
        val granted = bubbleManager.requestOverlayPermission(activity)
        if (granted) {
            _hasOverlayPermission.value = true
        }
        return granted
    }

    /**
     * 启用/禁用悬浮窗
     */
    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.setFloatingEnabled(enabled)
            if (enabled && bubbleManager.canDrawOverlays()) {
                startServiceIfNeeded()
            } else if (!enabled) {
                stopServiceIfRunning()
            }
        }
    }

    /**
     * 更新气泡大小
     */
    fun setBubbleSizeDp(size: Int) {
        val safeSize = size.coerceIn(40, 120)
        viewModelScope.launch {
            dataStore.setFloatingBubbleSize(safeSize)
            bubbleManager.setBubbleSize(safeSize)
        }
    }

    /**
     * 更新气泡透明度
     */
    fun setBubbleAlpha(alpha: Float) {
        val safeAlpha = alpha.coerceIn(0.3f, 1.0f)
        viewModelScope.launch {
            dataStore.setFloatingBubbleAlpha(safeAlpha)
            bubbleManager.setBubbleAlpha(safeAlpha)
        }
    }

    /**
     * 设置开机自启
     */
    fun setAutoStartOnBoot(enable: Boolean) {
        viewModelScope.launch {
            dataStore.setFloatingAutoStart(enable)
        }
    }

    /**
     * 立即显示气泡（调试/用户手动触发）
     */
    fun showBubbleNow() {
        if (!bubbleManager.canDrawOverlays()) return
        startServiceIfNeeded()
        viewModelScope.launch {
            kotlinx.coroutines.delay(300)
            FloatingBubbleService.sendShowBubble(context)
        }
    }

    /**
     * 隐藏气泡
     */
    fun hideBubbleNow() {
        FloatingBubbleService.sendHideBubble(context)
    }

    /**
     * 测试切换状态（用于UI预览）
     */
    fun testSetState(state: FloatingBubbleState) {
        FloatingBubbleService.sendUpdateState(context, state)
        viewModelScope.launch {
            kotlinx.coroutines.delay(3000)
            FloatingBubbleService.sendUpdateState(context, FloatingBubbleState.STANDBY)
        }
    }

    // ==================== Service 启停 ====================

    private fun startServiceIfNeeded() {
        if (isServiceActuallyRunning()) {
            _isServiceRunning.value = true
            return
        }
        val intent = FloatingBubbleService.createStartIntent(context)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        runCatching {
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            isBound = true
        }
    }

    private fun stopServiceIfRunning() {
        if (isBound) {
            runCatching { context.unbindService(serviceConnection) }
            isBound = false
        }
        boundService = null
        val intent = FloatingBubbleService.createStopIntent(context)
        runCatching { context.stopService(intent) }
        _isServiceRunning.value = false
    }

    private fun isServiceActuallyRunning(): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val runningServices = manager?.getRunningServices(Integer.MAX_VALUE) ?: return false
        for (service in runningServices) {
            if (FloatingBubbleService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }

    // ==================== 设置同步到 BubbleManager ====================

    private fun applySettingsToRunningBubble() {
        viewModelScope.launch {
            val settings = uiState.value
            bubbleManager.setBubbleSize(settings.bubbleSizeDp)
            bubbleManager.setBubbleAlpha(settings.bubbleAlpha)
        }
    }

    // ==================== ViewModel 生命周期 ====================

    override fun onCleared() {
        super.onCleared()
        if (isBound) {
            runCatching { context.unbindService(serviceConnection) }
            isBound = false
        }
    }
}

private data class CombinedSettings(
    val enabled: Boolean,
    val sizeDp: Int,
    val alpha: Float,
    val autoStart: Boolean,
    val state: FloatingBubbleState
)
