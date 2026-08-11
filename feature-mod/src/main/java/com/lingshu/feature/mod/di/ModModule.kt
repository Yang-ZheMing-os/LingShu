package com.lingshu.feature.mod.di

import com.lingshu.feature.mod.data.IScriptEngine
import com.lingshu.feature.mod.data.IQuickJsEngine
import com.lingshu.feature.mod.data.MockScriptEngine
import com.lingshu.feature.mod.data.ModServiceImpl
import com.lingshu.feature.mod.domain.IModService
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ModModule {

    @Provides
    @Singleton
    fun provideScriptEngine(): IScriptEngine {
        return MockScriptEngine()
    }

    @Provides
    @Singleton
    fun provideModService(
        scriptEngine: IScriptEngine
    ): IModService {
        return ModServiceImpl(scriptEngine)
    }
}
