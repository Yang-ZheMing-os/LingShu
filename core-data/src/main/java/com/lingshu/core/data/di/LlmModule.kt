package com.lingshu.core.data.di

import android.content.Context
import com.lingshu.core.data.llm.DeepSeekProvider
import com.lingshu.core.data.llm.GeminiProvider
import com.lingshu.core.data.llm.ILlmProvider
import com.lingshu.core.data.llm.LlmConfigStore
import com.lingshu.core.data.llm.OllamaProvider
import com.lingshu.core.data.llm.OpenAiProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LlmModule {

    @Provides
    @Singleton
    @DeepSeek
    fun bindDeepSeekProvider(impl: DeepSeekProvider): ILlmProvider = impl

    @Provides
    @Singleton
    @Ollama
    fun bindOllamaProvider(impl: OllamaProvider): ILlmProvider = impl

    @Provides
    @Singleton
    @Gemini
    fun bindGeminiProvider(impl: GeminiProvider): ILlmProvider = impl

    @Provides
    @Singleton
    @OpenAi
    fun bindOpenAiProvider(impl: OpenAiProvider): ILlmProvider = impl

    @Provides
    @Singleton
    @Qwen
    fun bindQwenProvider(impl: OpenAiProvider): ILlmProvider = impl

    @Provides
    @Singleton
    fun provideLlmConfigStore(@ApplicationContext context: Context): LlmConfigStore {
        return LlmConfigStore(context)
    }
}

@javax.inject.Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DeepSeek

@javax.inject.Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class Ollama

@javax.inject.Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class Gemini

@javax.inject.Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class OpenAi

@javax.inject.Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class Qwen
