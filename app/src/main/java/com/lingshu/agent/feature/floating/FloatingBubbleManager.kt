package com.lingshu.agent.feature.floating

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.lingshu.agent.core.data.AppSettingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 悬浮窗管理器
 *
 * 封装 Android 系统级悬浮窗（TYPE_APPLICATION_OVERLAY）
 * 通过 WindowManager.addView() 将 ComposeView 添加到系统窗口层。
 *
 * 核心能力：
 * 1. 权限检查 & 申请：requestOverlayPermission(Activity)
 * 2. 气泡显示/隐藏：show() / hide() / dismiss()
 * 3. 4 种状态切换：STANDBY / AWAKENED / THINKING / EXECUTING
 * 4. 拖拽移动：onTouchListener + Fling 边缘回弹
 * 5. 单击：弹出快捷对话 ComposeView
 * 6. 长按：进入设置模式（透明度/大小调整）
 */
@Singleton
class FloatingBubbleManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: AppSettingsDataStore
) : LifecycleOwner {

    companion object {
        private const val DRAG_THRESHOLD_PX = 12
        private const val FLING_DURATION_MS = 280L
        private const val EDGE_SNAP_RATIO = 0.15f
        const val DEFAULT_BUBBLE_SIZE_DP = 64
        const val DEFAULT_ALPHA = 0.92f
    }

    private val lifecycleRegistry: LifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryOwner = object : SavedStateRegistryOwner {
        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry by lazy { savedStateRegistryController.savedStateRegistry }
    }

    private val savedStateRegistryController: SavedStateRegistryController =
        SavedStateRegistryController.create(savedStateRegistryOwner)

    override val lifecycle: Lifecycle get() = lifecycleRegistry

    init {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    private fun attachToLifecycle() {
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    private fun detachFromLifecycle() {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val windowManager: WindowManager by lazy {
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    private val displayMetrics: DisplayMetrics by lazy {
        context.resources.displayMetrics
    }

    private var bubbleContainer: FrameLayout? = null
    private var bubbleComposeView: ComposeView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var chatContainer: FrameLayout? = null
    private var chatComposeView: ComposeView? = null
    private var chatParams: WindowManager.LayoutParams? = null

    private val _bubbleState = MutableStateFlow(FloatingBubbleState.STANDBY)
    val bubbleState: StateFlow<FloatingBubbleState> = _bubbleState.asStateFlow()

    private val _isVisible = MutableStateFlow(false)
    val isVisible: StateFlow<Boolean> = _isVisible.asStateFlow()

    private val _isChatVisible = MutableStateFlow(false)
    val isChatVisible: StateFlow<Boolean> = _isChatVisible.asStateFlow()

    private val _bubbleSizeDp = MutableStateFlow(DEFAULT_BUBBLE_SIZE_DP)
    val bubbleSizeDp: StateFlow<Int> = _bubbleSizeDp.asStateFlow()

    private val _bubbleAlpha = MutableStateFlow(DEFAULT_ALPHA)
    val bubbleAlpha: StateFlow<Float> = _bubbleAlpha.asStateFlow()

    private val _bubbleX = MutableStateFlow(0f)
    val bubbleX: StateFlow<Float> = _bubbleX.asStateFlow()

    private val _bubbleY = MutableStateFlow(0f)
    val bubbleY: StateFlow<Float> = _bubbleY.asStateFlow()

    @Volatile
    private var isDragging = false
    private var touchStartRawX = 0f
    private var touchStartRawY = 0f
    private var viewStartX = 0f
    private var viewStartY = 0f
    private var downTime = 0L

    var onBubbleClick: (() -> Unit)? = null
    var onBubbleLongClick: (() -> Unit)? = null
    var onChatSend: ((String) -> Unit)? = null
    var onQuickCommand: ((String) -> Unit)? = null

    fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    suspend fun requestOverlayPermission(activity: Activity): Boolean {
        if (canDrawOverlays()) return true
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
        return false
    }

    fun show() {
        if (!canDrawOverlays()) return
        if (bubbleContainer != null) {
            bubbleContainer?.visibility = View.VISIBLE
            _isVisible.value = true
            return
        }
        attachToLifecycle()
        createBubbleView()
        _isVisible.value = true
    }

    fun hide() {
        bubbleContainer?.visibility = View.GONE
        _isVisible.value = false
        hideChatPanel()
    }

    fun dismiss() {
        runCatching {
            bubbleContainer?.let { windowManager.removeView(it) }
            chatContainer?.let { windowManager.removeView(it) }
        }
        bubbleContainer = null
        bubbleComposeView = null
        chatContainer = null
        chatComposeView = null
        _isVisible.value = false
        _isChatVisible.value = false
        detachFromLifecycle()
    }

    fun setState(state: FloatingBubbleState) {
        _bubbleState.value = state
    }

    fun setBubbleSize(sizeDp: Int) {
        _bubbleSizeDp.value = sizeDp.coerceIn(40, 120)
    }

    fun setBubbleAlpha(alpha: Float) {
        _bubbleAlpha.value = alpha.coerceIn(0.3f, 1.0f)
    }

    private fun createBubbleView() {
        val ctx = context.applicationContext

        val container = FrameLayout(ctx)
        container.setViewTreeLifecycleOwner(this)
        container.setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)

        val compose = ComposeView(ctx).apply {
            setContent {
                val state = _bubbleState.value
                val size = _bubbleSizeDp.value
                val alpha = _bubbleAlpha.value
                FloatingBubbleView(
                    state = state,
                    sizeDp = size,
                    alpha = alpha,
                    onClick = { handleBubbleClick() },
                    onLongClick = { handleBubbleLongClick() },
                    onDrag = { dx, dy -> handleDragDelta(dx, dy) }
                )
            }
        }

        container.addView(compose, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val screenW = displayMetrics.widthPixels
            val screenH = displayMetrics.heightPixels
            val bubblePx = (DEFAULT_BUBBLE_SIZE_DP * displayMetrics.density).toInt()

            // 尝试从 DataStore 恢复上次位置
            var restored = false
            scope.launch(Dispatchers.Main) {
                val savedX = dataStore.floatingBubblePosXFlow.firstOrNull() ?: -1f
                val savedY = dataStore.floatingBubblePosYFlow.firstOrNull() ?: -1f
                if (savedX >= 0f && savedY >= 0f) {
                    x = savedX.toInt().coerceIn(0, (screenW - bubblePx).toInt())
                    y = savedY.toInt().coerceIn(0, (screenH - bubblePx * 2).toInt())
                } else {
                    // 默认位置：屏幕右下角
                    x = screenW - bubblePx - (8 * displayMetrics.density).toInt()
                    y = (screenH * 0.35f).toInt()
                }
                _bubbleX.value = x.toFloat()
                _bubbleY.value = y.toFloat()
            }
            // 同步设置初始默认（协程完成前使用默认值）
            x = screenW - bubblePx - (8 * displayMetrics.density).toInt()
            y = (screenH * 0.35f).toInt()
            _bubbleX.value = x.toFloat()
            _bubbleY.value = y.toFloat()
        }

        container.setOnTouchListener(createBubbleTouchListener())

        runCatching {
            windowManager.addView(container, params)
        }.onFailure {
            return
        }

        bubbleContainer = container
        bubbleComposeView = compose
        bubbleParams = params
    }

    private fun createBubbleTouchListener(): View.OnTouchListener {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var longPressRunnable: Runnable? = null
        return View.OnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    touchStartRawX = event.rawX
                    touchStartRawY = event.rawY
                    viewStartX = bubbleParams?.x?.toFloat() ?: 0f
                    viewStartY = bubbleParams?.y?.toFloat() ?: 0f
                    downTime = System.currentTimeMillis()
                    longPressRunnable = Runnable {
                        if (!isDragging) {
                            handleBubbleLongClick()
                            isDragging = true
                        }
                    }
                    handler.postDelayed(longPressRunnable!!, 600)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchStartRawX
                    val dy = event.rawY - touchStartRawY
                    if (!isDragging && (abs(dx) > DRAG_THRESHOLD_PX || abs(dy) > DRAG_THRESHOLD_PX)) {
                        isDragging = true
                        longPressRunnable?.let { handler.removeCallbacks(it) }
                    }
                    if (isDragging) {
                        updateBubblePosition(
                            (viewStartX + dx).toInt(),
                            (viewStartY + dy).toInt()
                        )
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { handler.removeCallbacks(it) }
                    longPressRunnable = null
                    val wasDrag = isDragging
                    val upTime = System.currentTimeMillis()
                    isDragging = false
                    if (!wasDrag && (upTime - downTime) < 500) {
                        v.performClick()
                    } else if (wasDrag) {
                        performFlingSnapToEdge()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun handleDragDelta(dx: Float, dy: Float) {
        val params = bubbleParams ?: return
        updateBubblePosition(
            (params.x + dx).toInt(),
            (params.y + dy).toInt()
        )
    }

    private fun updateBubblePosition(x: Int, y: Int) {
        val params = bubbleParams ?: return
        val container = bubbleContainer ?: return
        val screenW = displayMetrics.widthPixels
        val screenH = displayMetrics.heightPixels
        val bubblePx = (bubbleSizeDp.value * displayMetrics.density).toInt()
        val clampedX = x.coerceIn(0, (screenW - bubblePx).toInt())
        val clampedY = y.coerceIn(0, (screenH - bubblePx * 1.5f).toInt())
        params.x = clampedX
        params.y = clampedY
        _bubbleX.value = clampedX.toFloat()
        _bubbleY.value = clampedY.toFloat()
        runCatching { windowManager.updateViewLayout(container, params) }
    }

    private fun performFlingSnapToEdge() {
        val params = bubbleParams ?: return
        val screenW = displayMetrics.widthPixels
        val currentX = params.x
        val bubblePx = (bubbleSizeDp.value * displayMetrics.density).toInt()
        val centerX = currentX + bubblePx / 2f
        val threshold = screenW * EDGE_SNAP_RATIO
        val targetX = if (centerX < screenW / 2f) {
            if (centerX < screenW * 0.5f - threshold) 0 else currentX
        } else {
            if (centerX > screenW * 0.5f + threshold) {
                screenW - bubblePx
            } else currentX
        }
        scope.launch(Dispatchers.Main) {
            val startX = params.x
            val delta = targetX - startX
            val steps = 20
            val stepDuration = FLING_DURATION_MS / steps
            for (i in 1..steps) {
                val progress = i.toFloat() / steps
                val eased = 1f - Math.pow(1.0 - progress.toDouble(), 3.0).toFloat()
                val newX = (startX + delta * eased).roundToInt()
                updateBubblePosition(newX, params.y)
                delay(stepDuration)
            }
            updateBubblePosition(targetX, params.y)
            // 拖拽结束后持久化位置
            persistPosition(params.x.toFloat(), params.y.toFloat())
        }
    }

    private fun handleBubbleClick() {
        if (_isChatVisible.value) {
            hideChatPanel()
        } else {
            if (onBubbleClick != null) {
                onBubbleClick?.invoke()
            } else {
                showChatPanel()
            }
        }
    }

    private fun handleBubbleLongClick() {
        onBubbleLongClick?.invoke()
    }

    fun showChatPanel() {
        if (_isChatVisible.value) return
        if (chatContainer == null) {
            createChatPanel()
        }
        chatContainer?.visibility = View.VISIBLE
        _isChatVisible.value = true
        positionChatNearBubble()
    }

    fun hideChatPanel() {
        chatContainer?.visibility = View.GONE
        _isChatVisible.value = false
    }

    private fun createChatPanel() {
        val ctx = context.applicationContext
        val container = FrameLayout(ctx)
        container.setViewTreeLifecycleOwner(this)
        val compose = ComposeView(ctx).apply {
            setContent {
                QuickChatPanel(
                    onDismiss = { hideChatPanel() },
                    onSend = { msg ->
                        onChatSend?.invoke(msg)
                        hideChatPanel()
                    },
                    onQuickCommand = { cmd ->
                        onQuickCommand?.invoke(cmd)
                        hideChatPanel()
                    }
                )
            }
        }
        container.addView(compose, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        runCatching { windowManager.addView(container, params) }
        chatContainer = container
        chatComposeView = compose
        chatParams = params
    }

    private fun positionChatNearBubble() {
        val params = chatParams ?: return
        val container = chatContainer ?: return
        val bubbleXVal = _bubbleX.value.toInt()
        val bubbleYVal = _bubbleY.value.toInt()
        val screenW = displayMetrics.widthPixels
        val bubblePx = (_bubbleSizeDp.value * displayMetrics.density).toInt()
        if (bubbleXVal > screenW / 2) {
            params.x = bubbleXVal - (300 * displayMetrics.density).toInt()
        } else {
            params.x = bubbleXVal + bubblePx
        }
        params.y = bubbleYVal
        runCatching { windowManager.updateViewLayout(container, params) }
    }

    /**
     * 持久化悬浮窗当前位置到 DataStore
     */
    private fun persistPosition(x: Float, y: Float) {
        scope.launch(Dispatchers.IO) {
            dataStore.setFloatingBubblePosition(x, y)
        }
    }
}
