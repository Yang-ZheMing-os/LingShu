package com.lingshu.agent.feature.proactive.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.lingshu.agent.feature.proactive.ProactiveCareRepository
import com.lingshu.agent.feature.proactive.services.ProactiveCareService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 屏幕解锁广播监听器
 *
 * 监听系统的屏幕解锁广播（ACTION_USER_PRESENT），用于：
 * 1. 记录解锁事件到 Repository，供决策引擎判断"频繁解锁"检测使用
 * 2. 解锁后立即触发一次主动关怀检测（用户回到手机的时机最自然）
 *
 * 注册方式：
 * - AndroidManifest.xml 中静态注册（推荐，保证App未启动时也能收到）
 * - 或在 Service 中动态注册
 *
 * 注意：
 * - Android 8.0+ 对静态广播有诸多限制，但 ACTION_USER_PRESENT 不受限
 * - 实际项目中建议同时使用 UsageStats 检测前台App更准确，此Receiver作为补充
 */
@AndroidEntryPoint
class ScreenUnlockReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: ProactiveCareRepository

    /** 协程作用域（仅用于短耗时的异步写入操作） */
    private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        /** 上次解锁记录节流时间（毫秒），避免快速重复记录（如同时收到多次解锁广播） */
        private const val DEBOUNCE_MS = 3000L

        /** 上次成功处理时间戳（进程内静态节流，注意App重启后会失效） */
        @Volatile
        private var lastProcessedTimestamp: Long = 0L
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context ?: return
        val action = intent?.action ?: return

        when (action) {
            // 用户解锁设备（设备处于交互状态）
            Intent.ACTION_USER_PRESENT -> {
                handleUserPresent(ctx)
            }
            // 屏幕点亮（可选，用于更精细的统计）
            Intent.ACTION_SCREEN_ON -> {
                handleScreenOn(ctx)
            }
            // 屏幕关闭（可选，用于重置一些状态）
            Intent.ACTION_SCREEN_OFF -> {
                handleScreenOff(ctx)
            }
        }
    }

    /**
     * 处理用户解锁完成事件
     * 这是主要的解锁检测入口
     */
    private fun handleUserPresent(context: Context) {
        val now = System.currentTimeMillis()

        // 简单的进程内节流，避免瞬间重复广播
        if (now - lastProcessedTimestamp < DEBOUNCE_MS) {
            return
        }
        lastProcessedTimestamp = now

        // 1. 异步记录这次解锁事件
        receiverScope.launch {
            try {
                repository.recordScreenUnlock()
            } catch (_: Exception) {
                // 记录失败不影响主流程
            }
        }

        // 2. 触发一次主动关怀检测（通过Service）
        // 解锁时机是触发关怀的好时机：用户正在使用手机，不会显得突兀
        triggerProactiveCheck(context)
    }

    /**
     * 处理屏幕点亮（可选处理）
     * 部分机型会先发送 SCREEN_ON，然后成功解锁后发送 USER_PRESENT
     * 此处预留，目前与记录点亮时间，供后续统计手机使用习惯
     */
    private fun handleScreenOn(context: Context) {
        // 预留：可用于计算从点亮到解锁的时间差（判断用户是否急于解锁）
        // 暂不实现，交给决策引擎直接使用
    }

    /**
     * 处理屏幕关闭事件（可选处理）
     * 可用于：
     * - 重置连续使用时长的结算（从上次解锁到现在的时长统计
     * - 写入数据库或DataStore供后续行为分析
     */
    private fun handleScreenOff(context: Context) {
        // 预留：关闭屏幕时的处理逻辑
    }

    /**
     * 启动/唤醒 主动关怀Service执行一次检测
     */
    private fun triggerProactiveCheck(context: Context) {
        try {
            val serviceIntent = Intent(context, ProactiveCareService::class.java).apply {
                action = ProactiveCareService.ACTION_CHECK_NOW
            }
            // Android O及以上使用startForegroundService启动
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (_: Exception) {
            // 启动失败静默处理（如用户禁止后台启动权限）
        }
    }
}
