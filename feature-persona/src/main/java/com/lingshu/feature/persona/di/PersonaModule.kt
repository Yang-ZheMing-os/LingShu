package com.lingshu.feature.persona.di

import com.lingshu.core.data.datastore.AppPreferences
import com.lingshu.feature.persona.data.PersonaPromptGenerator
import com.lingshu.feature.persona.data.PersonaServiceImpl
import com.lingshu.feature.persona.data.SentimentAnalyzer
import com.lingshu.feature.persona.domain.IPersonaService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PersonaModule {

    @Provides
    @Singleton
    fun provideSentimentAnalyzer(): SentimentAnalyzer {
        return SentimentAnalyzer()
    }

    @Provides
    @Singleton
    fun providePersonaPromptGenerator(): PersonaPromptGenerator {
        return PersonaPromptGenerator()
    }

    @Provides
    @Singleton
    fun providePersonaService(
        appPreferences: AppPreferences,
        sentimentAnalyzer: SentimentAnalyzer,
        personaPromptGenerator: PersonaPromptGenerator
    ): IPersonaService {
        return PersonaServiceImpl(
            appPreferences = appPreferences,
            sentimentAnalyzer = sentimentAnalyzer,
            personaPromptGenerator = personaPromptGenerator
        )
    }
}
