package com.lingshu.feature.floating.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.core.common.event.FloatingSize
import com.lingshu.core.common.event.FloatingState
import com.lingshu.core.common.event.IFloatingService
import com.lingshu.feature.floating.presentation.FloatingBall
import com.lingshu.feature.floating.presentation.FloatingChatBubble
import com.lingshu.feature.floating.presentation.FloatingViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.abs

@AndroidEntryPoint
class FloatingWindowService : Service(), IFloatingService {

    @Inject
    lateinit var viewModel: FloatingViewModel

    private lateinit var windowManager: WindowManager
    private var floatingBallView: ComposeView? = null
    private var chatBubbleView: ComposeView? = null

    private var ballParams: WindowManager.LayoutParams? = null
    private var chatParams: WindowManager.LayoutParams? = null

    private var isBallShowing = false
    private var isChatShowing = false

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    private val savedStateRegistryController = SavedStateRegistryController.create(
        object : LifecycleOwner {
            override val lifecycle: Lifecycle
                get() = serviceLifecycle
        }
    )

    private val serviceLifecycle = ServiceLifecycle()

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        serviceLifecycle.onCreate()
        savedStateRegistryController.performRestore(null)
        LingShuLog.d("FloatingWindowService", "Service created")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun show() {
        if (!hasOverlayPermission()) {
            LingShuLog.w("FloatingWindowService", "No overlay permission")
            return
        }
        if (isBallShowing) return
        showFloatingBall()
    }

    override fun hide() {
        hideFloatingBall()
        hideChatBubble()
    }

    override fun toggleVisibility() {
        if (isBallShowing) hide() else show()
    }

    override fun updateState(state: FloatingState) {
        viewModel.updateState(state)
    }

    override fun setOpacity(opacity: Float) {
        viewModel.setOpacity(opacity)
    }

    override fun setSize(size: FloatingSize) {
        viewModel.setSize(size)
        updateBallSize(size)
    }

    override fun isShowing(): Boolean = isBallShowing

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun showFloatingBall() {
        if (floatingBallView == null) {
            floatingBallView = ComposeView(this).apply {
                setViewTreeLifecycleOwner(
                    object : LifecycleOwner {
                        override val lifecycle: Lifecycle
                            get() = serviceLifecycle
                    }
                )
                setViewTreeSavedStateRegistryOwner(
                    object : SavedStateRegistryOwner {
                        override val lifecycle: Lifecycle
                            get() = serviceLifecycle

                        override val savedStateRegistry: SavedStateRegistry
                            get() = savedStateRegistryController.savedStateRegistry
                    }
                )
                setContent {
                    FloatingBall(
                        state = viewModel.state.value,
                        size = viewModel.size.value,
                        opacity = viewModel.opacity.value,
                        onClick = {
                            toggleChatBubble()
                        }
                    )
                }
                setOnTouchListener(FloatingBallTouchListener())
            }
        }

        val size = viewModel.size.value.dp
        val density = resources.displayMetrics.density
        val pixelSize = (size * density).toInt()

        ballParams = WindowManager.LayoutParams(
            pixelSize,
            pixelSize,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        try {
            windowManager.addView(floatingBallView, ballParams)
            isBallShowing = true
            serviceLifecycle.onStart()
            serviceLifecycle.onResume()
            LingShuLog.d("FloatingWindowService", "Floating ball shown")
        } catch (e: Exception) {
            LingShuLog.e("FloatingWindowService", "Failed to show floating ball", e)
        }
    }

    private fun hideFloatingBall() {
        if (floatingBallView != null && isBallShowing) {
            try {
                windowManager.removeView(floatingBallView)
                isBallShowing = false
                LingShuLog.d("FloatingWindowService", "Floating ball hidden")
            } catch (e: Exception) {
                LingShuLog.e("FloatingWindowService", "Failed to hide floating ball", e)
            }
        }
    }

    private fun updateBallSize(size: FloatingSize) {
        if (floatingBallView != null && ballParams != null) {
            val density = resources.displayMetrics.density
            val pixelSize = (size.dp * density).toInt()
            ballParams?.width = pixelSize
            ballParams?.height = pixelSize
            try {
                windowManager.updateViewLayout(floatingBallView, ballParams)
            } catch (e: Exception) {
                LingShuLog.e("FloatingWindowService", "Failed to update ball size", e)
            }
        }
    }

    private fun toggleChatBubble() {
        if (isChatShowing) {
            hideChatBubble()
        } else {
            showChatBubble()
        }
    }

    private fun showChatBubble() {
        if (!isBallShowing || ballParams == null) return

        if (chatBubbleView == null) {
            chatBubbleView = ComposeView(this).apply {
                setViewTreeLifecycleOwner(
                    object : LifecycleOwner {
                        override val lifecycle: Lifecycle
                            get() = serviceLifecycle
                    }
                )
                setViewTreeSavedStateRegistryOwner(
                    object : SavedStateRegistryOwner {
                        override val lifecycle: Lifecycle
                            get() = serviceLifecycle

                        override val savedStateRegistry: SavedStateRegistry
                            get() = savedStateRegistryController.savedStateRegistry
                    }
                )
                setContent {
                    FloatingChatBubble(
                        onDismiss = { hideChatBubble() },
                        onSend = { message ->
                            viewModel.sendMessage(message)
                            hideChatBubble()
                        }
                    )
                }
            }
        }

        val density = resources.displayMetrics.density
        val chatWidth = (280 * density).toInt()
        val chatHeight = (200 * density).toInt()

        chatParams = WindowManager.LayoutParams(
            chatWidth,
            chatHeight,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ballParams!!.x
            y = ballParams!!.y - chatHeight - 20
        }

        try {
            windowManager.addView(chatBubbleView, chatParams)
            isChatShowing = true
            LingShuLog.d("FloatingWindowService", "Chat bubble shown")
        } catch (e: Exception) {
            LingShuLog.e("FloatingWindowService", "Failed to show chat bubble", e)
        }
    }

    private fun hideChatBubble() {
        if (chatBubbleView != null && isChatShowing) {
            try {
                windowManager.removeView(chatBubbleView)
                isChatShowing = false
                LingShuLog.d("FloatingWindowService", "Chat bubble hidden")
            } catch (e: Exception) {
                LingShuLog.e("FloatingWindowService", "Failed to hide chat bubble", e)
            }
        }
    }

    private inner class FloatingBallTouchListener : View.OnTouchListener {
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = ballParams?.x ?: 0
                    initialY = ballParams?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - initialTouchX).toInt()
                    val deltaY = (event.rawY - initialTouchY).toInt()

                    if (abs(deltaX) > 10 || abs(deltaY) > 10) {
                        isDragging = true
                    }

                    if (isDragging) {
                        ballParams?.x = initialX + deltaX
                        ballParams?.y = initialY + deltaY
                        try {
                            windowManager.updateViewLayout(floatingBallView, ballParams)
                            if (isChatShowing && chatParams != null) {
                                val density = resources.displayMetrics.density
                                val chatHeight = (200 * density).toInt()
                                chatParams?.x = ballParams!!.x
                                chatParams?.y = ballParams!!.y - chatHeight - 20
                                windowManager.updateViewLayout(chatBubbleView, chatParams)
                            }
                        } catch (e: Exception) {
                            LingShuLog.e("FloatingWindowService", "Failed to move floating ball", e)
                        }
                        return true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        view.performClick()
                    }
                    return true
                }
            }
            return false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        hide()
        serviceLifecycle.onPause()
        serviceLifecycle.onStop()
        serviceLifecycle.onDestroy()
        LingShuLog.d("FloatingWindowService", "Service destroyed")
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, FloatingWindowService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingWindowService::class.java)
            context.stopService(intent)
        }
    }
}
