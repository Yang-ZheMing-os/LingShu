package com.lingshu.feature.control.di

import com.lingshu.core.common.event.ICommandSyncer
import com.lingshu.feature.control.data.CommandParserImpl
import com.lingshu.feature.control.data.SystemControlImpl
import com.lingshu.feature.control.data.scenes.SceneExecutorImpl
import com.lingshu.feature.control.data.scenes.SceneRepositoryImpl
import com.lingshu.feature.control.data.scenes.SceneResolverImpl
import com.lingshu.feature.control.domain.CommandSyncer
import com.lingshu.feature.control.domain.ICommandParser
import com.lingshu.feature.control.domain.ISystemControl
import com.lingshu.feature.control.domain.scenes.ISceneRepository
import com.lingshu.feature.control.domain.scenes.SceneExecutor
import com.lingshu.feature.control.domain.scenes.SceneResolver
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ControlModule {

    @Binds
    @Singleton
    abstract fun bindSystemControl(
        systemControl: SystemControlImpl
    ): ISystemControl

    @Binds
    @Singleton
    abstract fun bindCommandParser(
        commandParser: CommandParserImpl
    ): ICommandParser

    @Binds
    @Singleton
    abstract fun bindCommandSyncer(
        syncer: CommandSyncer
    ): ICommandSyncer

    @Binds
    @Singleton
    abstract fun bindSceneRepository(
        impl: SceneRepositoryImpl
    ): ISceneRepository

    @Binds
    @Singleton
    abstract fun bindSceneResolver(
        impl: SceneResolverImpl
    ): SceneResolver

    @Binds
    @Singleton
    abstract fun bindSceneExecutor(
        impl: SceneExecutorImpl
    ): SceneExecutor
}
