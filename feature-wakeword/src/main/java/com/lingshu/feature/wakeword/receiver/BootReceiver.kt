package com.lingshu.feature.wakeword.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.feature.wakeword.service.WakeWordService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            LingShuLog.i("BootReceiver", "收到开机广播，启动唤醒词服务")
            try {
                val serviceIntent = Intent(context, WakeWordService::class.java).apply {
                    action = WakeWordService.ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                LingShuLog.e("BootReceiver", "启动唤醒词服务失败", e)
            }
        }
    }
}
