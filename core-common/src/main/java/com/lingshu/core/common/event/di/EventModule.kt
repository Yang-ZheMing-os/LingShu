package com.lingshu.core.common.event.di

import com.lingshu.core.common.event.AppEventBusImpl
import com.lingshu.core.common.event.FloatingStateSyncer
import com.lingshu.core.common.event.IAppEventBus
import com.lingshu.core.common.event.SttToChatBridge
import com.lingshu.core.common.event.WakeWordToSttBridge
import com.lingshu.core.common.log.LingShuLog
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
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

    @Provides
    @Singleton
    fun provideWakeWordToSttBridge(
        bridge: WakeWordToSttBridge
    ): WakeWordToSttBridge {
        LingShuLog.d("EventModule", "提供 WakeWordToSttBridge 单例")
        return bridge
    }

    @Provides
    @Singleton
    fun provideSttToChatBridge(
        bridge: SttToChatBridge
    ): SttToChatBridge {
        LingShuLog.d("EventModule", "提供 SttToChatBridge 单例")
        return bridge
    }

    @Provides
    @Singleton
    fun provideFloatingStateSyncer(
        syncer: FloatingStateSyncer
    ): FloatingStateSyncer {
        LingShuLog.d("EventModule", "提供 FloatingStateSyncer 单例")
        return syncer
    }

    @Provides
    @Singleton
    fun provideEventBridgesStarter(
        wakeWordToSttBridge: WakeWordToSttBridge,
        sttToChatBridge: SttToChatBridge,
        floatingStateSyncer: FloatingStateSyncer
    ): EventBridgesStarter {
        LingShuLog.d("EventModule", "创建 EventBridgesStarter，用于统一启动所有 Bridge")
        return EventBridgesStarter(
            wakeWordToSttBridge,
            sttToChatBridge,
            floatingStateSyncer
        )
    }
}

class EventBridgesStarter(
    private val wakeWordToSttBridge: WakeWordToSttBridge,
    private val sttToChatBridge: SttToChatBridge,
    private val floatingStateSyncer: FloatingStateSyncer
) {
    fun startAll() {
        LingShuLog.i("EventBridgesStarter", "========== 启动所有事件桥梁 ==========")
        wakeWordToSttBridge.start()
        sttToChatBridge.start()
        floatingStateSyncer.start()
        LingShuLog.i("EventBridgesStarter", "========== 事件桥梁全部启动完成 ==========")
    }

    fun stopAll() {
        LingShuLog.i("EventBridgesStarter", "========== 停止所有事件桥梁 ==========")
        wakeWordToSttBridge.stop()
        sttToChatBridge.stop()
        floatingStateSyncer.stop()
        LingShuLog.i("EventBridgesStarter", "========== 事件桥梁全部停止完成 ==========")
    }
}
