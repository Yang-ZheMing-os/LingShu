package com.lingshu.feature.clonevoice.di

import com.lingshu.feature.clonevoice.data.CloneVoiceServiceImpl
import com.lingshu.feature.clonevoice.domain.ICloneVoiceService
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 声音克隆模块 DI 配置。
 * 通过 @Binds 让 Hilt 使用 CloneVoiceServiceImpl 的 @Inject 构造函数
 * （Context + OfflineTtsRouter）自动装配，确保 OfflineTtsRouter 能注入到实现中。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CloneVoiceModule {

    @Binds
    @Singleton
    abstract fun bindCloneVoiceService(impl: CloneVoiceServiceImpl): ICloneVoiceService
}
