package com.lingshu.feature.proactive.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.feature.proactive.domain.IProactiveService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 开机自启广播：
 * 设备重启后（RECEIVE_BOOT_COMPLETED），从持久化配置读取 enabled=true 的话，
 * 自动重新启动 ProactiveCheckWorker，保证主动关怀服务不中断。
 *
 * 没有这个的话，用户重启手机后，冷启动虽然会走 Application.onCreate 恢复，
 * 但加一层 BootReceiver 是双保险（某些定制 ROM 在开机广播前就限制后台启动）。
 */
@AndroidEntryPoint
class ProactiveBootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var proactiveService: IProactiveService

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        LingShuLog.i(TAG, "收到广播: action=$action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                LingShuLog.i(TAG, "设备开机/应用更新，准备恢复主动关怀服务...")
                val pending = goAsync()
                scope.launch {
                    runCatching {
                        val config = proactiveService.getConfig()
                        if (config.enabled) {
                            LingShuLog.i(TAG, "持久化配置 enabled=true → 自动启动 Worker")
                            proactiveService.start()
                        } else {
                            LingShuLog.i(TAG, "持久化配置 enabled=false → 不启动服务")
                        }
                    }.onFailure { e ->
                        LingShuLog.e(TAG, "开机恢复主动关怀失败", e)
                    }
                    pending.finish()
                }
            }
            else -> {
                LingShuLog.d(TAG, "未处理的 action: $action")
            }
        }
    }

    companion object {
        private const val TAG = "ProactiveBoot"
    }
}
