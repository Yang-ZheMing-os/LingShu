package com.lingshu.feature.clonevoice.di

import android.content.Context
import com.lingshu.feature.clonevoice.data.CloneVoiceServiceImpl
import com.lingshu.feature.clonevoice.domain.ICloneVoiceService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CloneVoiceModule {

    @Provides
    @Singleton
    fun provideCloneVoiceService(@ApplicationContext context: Context): ICloneVoiceService {
        return CloneVoiceServiceImpl(context)
    }
}
