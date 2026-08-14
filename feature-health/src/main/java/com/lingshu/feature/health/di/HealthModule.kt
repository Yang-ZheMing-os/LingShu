package com.lingshu.feature.health.di

import android.content.Context
import com.lingshu.feature.health.data.HealthServiceImpl
import com.lingshu.feature.health.domain.IHealthService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object HealthModule {

    @Provides
    @Singleton
    fun provideHealthService(
        @ApplicationContext context: Context
    ): IHealthService {
        return HealthServiceImpl(context)
    }
}
