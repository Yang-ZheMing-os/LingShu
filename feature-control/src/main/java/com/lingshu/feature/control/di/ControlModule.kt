package com.lingshu.feature.control.di

import com.lingshu.feature.control.data.CommandParserImpl
import com.lingshu.feature.control.data.SystemControlImpl
import com.lingshu.feature.control.domain.ICommandParser
import com.lingshu.feature.control.domain.ISystemControl
import dagger.Binds
import dagger.Module
import com.lingshu.core.common.event.StartableBridge
import dagger.multibindings.IntoSet
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
}
