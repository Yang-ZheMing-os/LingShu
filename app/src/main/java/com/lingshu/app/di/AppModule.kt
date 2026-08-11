package com.lingshu.app.di

import com.lingshu.app.navigation.AppNavigatorImpl
import com.lingshu.core.common.navigation.IAppNavigator
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindAppNavigator(impl: AppNavigatorImpl): IAppNavigator
}
