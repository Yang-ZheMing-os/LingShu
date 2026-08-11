package com.lingshu.feature.proactive.di

import android.content.Context
import com.lingshu.core.data.datastore.AppPreferences
import com.lingshu.feature.proactive.data.NotificationActionReceiver
import com.lingshu.feature.proactive.data.ProactiveServiceImpl
import com.lingshu.feature.proactive.data.cooldown.CooldownManager
import com.lingshu.feature.proactive.data.generator.ContentGenerator
import com.lingshu.feature.proactive.data.trigger.TriggerEvaluator
import com.lingshu.feature.proactive.domain.IProactiveService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ProactiveModule {

    @Provides
    @Singleton
    fun provideTriggerEvaluator(@ApplicationContext context: Context): TriggerEvaluator {
        return TriggerEvaluator(context)
    }

    @Provides
    @Singleton
    fun provideContentGenerator(): ContentGenerator {
        return ContentGenerator()
    }

    @Provides
    @Singleton
    fun provideCooldownManager(@ApplicationContext context: Context): CooldownManager {
        return CooldownManager(context)
    }

    @Provides
    @Singleton
    fun provideProactiveService(
        @ApplicationContext context: Context,
        appPreferences: AppPreferences,
        triggerEvaluator: TriggerEvaluator,
        contentGenerator: ContentGenerator,
        cooldownManager: CooldownManager
    ): IProactiveService {
        return ProactiveServiceImpl(
            context = context,
            appPreferences = appPreferences,
            triggerEvaluator = triggerEvaluator,
            contentGenerator = contentGenerator,
            cooldownManager = cooldownManager
        )
    }
}
