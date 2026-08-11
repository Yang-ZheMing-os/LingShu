package com.lingshu.feature.clonevoice.di

import com.lingshu.feature.clonevoice.data.CloneVoiceServiceImpl
import com.lingshu.feature.clonevoice.domain.ICloneVoiceService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CloneVoiceModule {

    @Provides
    @Singleton
    fun provideCloneVoiceService(): ICloneVoiceService {
        return CloneVoiceServiceImpl()
    }
}
