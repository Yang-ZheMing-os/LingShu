package com.lingshu.feature.stt.di

import com.lingshu.feature.stt.data.CompositeSttEngine
import com.lingshu.core.common.event.ISttEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SttModule {

    @Binds
    @Singleton
    abstract fun bindSttEngine(
        compositeSttEngine: CompositeSttEngine
    ): ISttEngine
}
