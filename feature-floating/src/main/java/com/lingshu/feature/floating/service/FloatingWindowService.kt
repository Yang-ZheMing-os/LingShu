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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat

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

    private val serviceLifecycle = ServiceLifecycle()

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        serviceLifecycle.performRestore(null)
        serviceLifecycle.onCreate()
        startForegroundNotification()
        LingShuLog.d("FloatingWindowService", "Service created")
    }

    override fun onBind(intent: Intent?): IBinder? = FloatingServiceBinder(this)

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

    private fun startForegroundNotification() {
        val channelId = "lingshu_floating"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "悬浮窗服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示灵枢悬浮窗"
                setShowBadge(false)
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("灵枢")
            .setContentText("AI 助手运行中")
            .setSmallIcon(android.R.drawable.presence_online)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            LingShuLog.d("FloatingWindowService", "startForeground called successfully")
        } catch (e: Exception) {
            LingShuLog.e("FloatingWindowService", "startForeground failed", e)
        }
    }

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
                setViewTreeLifecycleOwner(serviceLifecycle)
                setViewTreeSavedStateRegistryOwner(serviceLifecycle)
                setContent {
                    val reply by viewModel.streamingReply.collectAsState()
                    val sending by viewModel.isSending.collectAsState()
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
                setViewTreeLifecycleOwner(serviceLifecycle)
                setViewTreeSavedStateRegistryOwner(serviceLifecycle)
                setContent {
                    val reply by viewModel.streamingReply.collectAsState()
                    val sending by viewModel.isSending.collectAsState()
                    FloatingChatBubble(
                        streamingReply = reply,
                        isSending = sending,
                        onDismiss = { hideChatBubble() },
                        onSend = { message ->
                            viewModel.sendMessage(message)
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
                    // Edge snapping: move to nearest horizontal edge
                    val params = ballParams
                    if (params != null) {
                        val screenWidth = android.content.res.Resources.getSystem().displayMetrics.widthPixels
                        val currentX = params.x
                        val targetX = if (currentX + params.width / 2 < screenWidth / 2) {
                            0
                        } else {
                            screenWidth - params.width
                        }
                        val handler = android.os.Handler(android.os.Looper.getMainLooper())
                        val steps = 10
                        val stepDelta = (targetX - currentX) / steps
                        var step = 0
                        handler.post(object : Runnable {
                            override fun run() {
                                step++
                                if (step <= steps) {
                                    params.x = currentX + stepDelta * step
                                    try {
                                        windowManager.updateViewLayout(floatingBallView, params)
                                    } catch (e: Exception) {
                                        LingShuLog.e("FloatingWindowService", "Edge snap update failed", e)
                                    }
                                    handler.postDelayed(this, 16)
                                }
                            }
                        })
                    }
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
        private const val NOTIFICATION_ID = 1001
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

class FloatingServiceBinder(
    private val service: FloatingWindowService
) : android.os.Binder() {
    fun getService(): FloatingWindowService = service
}
