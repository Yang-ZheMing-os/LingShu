package com.lingshu.feature.control.di

import com.lingshu.core.common.event.StartableBridge
import com.lingshu.feature.control.domain.AiReplyToControlBridge
import com.lingshu.feature.control.domain.CommandExecutor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/**
 * 控制模块的 @Provides 模块（与 @Binds 的 ControlModule 分离，因 @IntoSet 需要 @Provides）。
 * 把 AiReplyToControlBridge 注册到 Set<StartableBridge>，由 EventBridgesStarter 统一收集。
 */
@Module
@InstallIn(SingletonComponent::class)
object ControlProviderModule {

    @Provides
    @IntoSet
    @Singleton
    fun provideAiReplyToControlBridgeAsStartable(bridge: AiReplyToControlBridge): StartableBridge = bridge
}