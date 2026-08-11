package com.lingshu.feature.floating.di

import com.lingshu.core.common.event.IFloatingService
import com.lingshu.feature.floating.service.FloatingServiceProxy
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class FloatingModule {

    @Binds
    @Singleton
    abstract fun bindFloatingService(
        proxy: FloatingServiceProxy
    ): IFloatingService
}
