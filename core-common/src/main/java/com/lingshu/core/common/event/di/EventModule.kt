package com.lingshu.core.common.event.di

import com.lingshu.core.common.event.AiReplyToTtsBridge
import com.lingshu.core.common.event.AppEventBusImpl
import com.lingshu.core.common.event.FloatingStateSyncer
import com.lingshu.core.common.event.IAppEventBus
import com.lingshu.core.common.event.SttToChatBridge
import com.lingshu.core.common.event.StartableBridge
import com.lingshu.core.common.event.WakeWordToSttBridge
import com.lingshu.core.common.log.LingShuLog
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object EventModule {

    @Provides
    @Singleton
    fun provideAppEventBus(): IAppEventBus {
        LingShuLog.d("EventModule", "创建 IAppEventBus 单例 (AppEventBusImpl)")
        return AppEventBusImpl()
    }

    // 各 Bridge 通过 @IntoSet 注册到 Set<StartableBridge>，EventBridgesStarter 统一收集启动
    // Bridge 自身通过 @Inject constructor 由 Hilt 自动构造，无需 @Provides 提供具体类型

    @Provides
    @IntoSet
    @Singleton
    fun provideWakeWordToSttBridgeAsStartable(bridge: WakeWordToSttBridge): StartableBridge = bridge

    @Provides
    @IntoSet
    @Singleton
    fun provideSttToChatBridgeAsStartable(bridge: SttToChatBridge): StartableBridge = bridge

    @Provides
    @IntoSet
    @Singleton
    fun provideFloatingStateSyncerAsStartable(syncer: FloatingStateSyncer): StartableBridge = syncer

    @Provides
    @IntoSet
    @Singleton
    fun provideAiReplyToTtsBridgeAsStartable(bridge: AiReplyToTtsBridge): StartableBridge = bridge

    @Provides
    @Singleton
    fun provideEventBridgesStarter(
        bridges: Set<@JvmSuppressWildcards StartableBridge>
    ): EventBridgesStarter {
        LingShuLog.d("EventModule", "创建 EventBridgesStarter，收集到 ${bridges.size} 个 Bridge")
        return EventBridgesStarter(bridges.toList())
    }
}

class EventBridgesStarter(
    private val bridges: List<StartableBridge>
) {
    fun startAll() {
        LingShuLog.i("EventBridgesStarter", "========== 启动所有事件桥梁 (${bridges.size} 个) ==========")
        bridges.forEach { bridge ->
            try {
                bridge.start()
            } catch (e: Exception) {
                LingShuLog.e("EventBridgesStarter", "Bridge 启动失败: ${bridge::class.simpleName}", e)
            }
        }
        LingShuLog.i("EventBridgesStarter", "========== 事件桥梁全部启动完成 ==========")
    }

    fun stopAll() {
        LingShuLog.i("EventBridgesStarter", "========== 停止所有事件桥梁 (${bridges.size} 个) ==========")
        bridges.forEach { bridge ->
            try {
                bridge.stop()
            } catch (e: Exception) {
                LingShuLog.e("EventBridgesStarter", "Bridge 停止失败: ${bridge::class.simpleName}", e)
            }
        }
        LingShuLog.i("EventBridgesStarter", "========== 事件桥梁全部停止完成 ==========")
    }
}