package com.lingshu.feature.wakeword.di

import android.content.Context
import com.lingshu.core.data.datastore.AppPreferences
import com.lingshu.feature.wakeword.data.FallbackWakeWordEngine
import com.lingshu.feature.wakeword.data.PorcupineWakeWordEngine
import com.lingshu.feature.wakeword.domain.IWakeWordEngine
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class WakeWordModule {

    @Binds
    @Singleton
    abstract fun bindWakeWordEngine(
        porcupineWakeWordEngine: PorcupineWakeWordEngine
    ): IWakeWordEngine

    companion object {
        private const val DEFAULT_KEYWORD = "灵枢灵枢"

        @Provides
        @Singleton
        fun provideFallbackWakeWordEngine(
            @ApplicationContext context: Context
        ): FallbackWakeWordEngine {
            return FallbackWakeWordEngine(
                context = context,
                keyword = DEFAULT_KEYWORD
            )
        }

        @Provides
        @Singleton
        fun providePorcupineWakeWordEngine(
            @ApplicationContext context: Context,
            fallbackEngine: FallbackWakeWordEngine,
            appPreferences: AppPreferences
        ): PorcupineWakeWordEngine {
            val accessKey = runBlocking {
                appPreferences.apiKey.firstOrNull()?.takeIf { it.isNotBlank() }
            }
            return PorcupineWakeWordEngine(
                context = context,
                fallbackEngine = fallbackEngine,
                accessKey = accessKey,
                keyword = DEFAULT_KEYWORD
            )
        }
    }
}
