package com.lingshu.feature.floating.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.lingshu.core.common.event.FloatingSize
import com.lingshu.core.common.event.FloatingState
import com.lingshu.core.common.event.IFloatingService
import com.lingshu.core.common.log.LingShuLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FloatingServiceProxy @Inject constructor(
    @ApplicationContext private val context: Context
) : IFloatingService {

    private val delegateRef = AtomicReference<IFloatingService?>(null)
    private val pendingActions = mutableListOf<(IFloatingService) -> Unit>()
    private var bound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? FloatingServiceBinder
            val delegate = binder?.getService()
            delegateRef.set(delegate)
            bound = true
            LingShuLog.i(
                "FloatingServiceProxy",
                "onServiceConnected: delegateBound=${delegate != null}, pendingActions=${pendingActions.size}"
            )
            if (delegate != null) {
                synchronized(pendingActions) {
                    pendingActions.forEach { it(delegate) }
                    pendingActions.clear()
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            delegateRef.set(null)
            bound = false
            LingShuLog.w("FloatingServiceProxy", "onServiceDisconnected")
        }
    }

    private fun ensureBound() {
        if (!bound) {
            try {
                val intent = Intent(context, FloatingWindowService::class.java)
                FloatingWindowService.start(context)
                val bindOk = context.bindService(
                    intent, serviceConnection,
                    Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
                )
                LingShuLog.d("FloatingServiceProxy", "ensureBound: bindService result=$bindOk")
            } catch (e: Exception) {
                LingShuLog.e("FloatingServiceProxy", "ensureBound failed: ${e.message}", e)
            }
        }
    }

    private fun runOrSchedule(action: (IFloatingService) -> Unit) {
        val current = delegateRef.get()
        if (current != null) {
            try {
                action(current)
            } catch (e: Exception) {
                LingShuLog.w("FloatingServiceProxy", "invoke delegate failed", e)
            }
            return
        }
        synchronized(pendingActions) {
            pendingActions += action
        }
        ensureBound()
    }

    override fun show() = runOrSchedule { it.show() }
    override fun hide() = runOrSchedule { it.hide() }
    override fun toggleVisibility() = runOrSchedule { it.toggleVisibility() }
    override fun updateState(state: FloatingState) = runOrSchedule { it.updateState(state) }
    override fun setOpacity(opacity: Float) = runOrSchedule { it.setOpacity(opacity) }
    override fun setSize(size: FloatingSize) = runOrSchedule { it.setSize(size) }
    override fun isShowing(): Boolean = delegateRef.get()?.isShowing() ?: false

    fun release() {
        if (bound) {
            try { context.unbindService(serviceConnection) } catch (_: Throwable) { }
            bound = false
            delegateRef.set(null)
        }
    }
}
