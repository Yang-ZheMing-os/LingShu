package com.lingshu.feature.health.di

import com.lingshu.feature.health.data.HealthServiceImpl
import com.lingshu.feature.health.data.IHealthConnectService
import com.lingshu.feature.health.data.ISensorService
import com.lingshu.feature.health.data.MockHealthConnectService
import com.lingshu.feature.health.data.MockSensorService
import com.lingshu.feature.health.domain.IHealthService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object HealthModule {

    @Provides
    @Singleton
    fun provideHealthConnectService(): IHealthConnectService {
        return MockHealthConnectService()
    }

    @Provides
    @Singleton
    fun provideSensorService(): ISensorService {
        return MockSensorService()
    }

    @Provides
    @Singleton
    fun provideHealthService(
        healthConnectService: IHealthConnectService,
        sensorService: ISensorService
    ): IHealthService {
        return HealthServiceImpl(healthConnectService, sensorService)
    }
}
