package com.lingshu.feature.proactive.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.feature.proactive.domain.TriggerType

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        LingShuLog.d("Proactive", "Notification action received: $action")

        val triggerTypeStr = action.removePrefix("com.lingshu.proactive.ACTION_")
        runCatching { TriggerType.valueOf(triggerTypeStr) }.onSuccess { triggerType ->
            handleAction(context, triggerType)
        }
    }

    private fun handleAction(context: Context?, triggerType: TriggerType) {
        when (triggerType) {
            TriggerType.DARK_WALKING -> {
                openFlashlight(context)
            }
            else -> {
                LingShuLog.d("Proactive", "Action handled for $triggerType")
            }
        }
    }

    private fun openFlashlight(context: Context?) {
        context ?: return
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return
            cameraManager.setTorchMode(cameraId, true)
        } catch (e: Exception) {
            LingShuLog.e("Proactive", "Failed to open flashlight", e)
        }
    }
}
