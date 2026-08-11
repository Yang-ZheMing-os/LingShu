package com.lingshu.app.di

import android.content.Context
import com.lingshu.app.navigation.AppNavigatorImpl
import com.lingshu.core.common.navigation.IAppNavigator
import com.lingshu.core.data.datastore.AppPreferences
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindAppNavigator(impl: AppNavigatorImpl): IAppNavigator

    companion object {
        @Provides
        @Singleton
        fun provideAppPreferences(@ApplicationContext context: Context): AppPreferences {
            return AppPreferences(context)
        }
    }
}
