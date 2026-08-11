package com.lingshu.feature.chat.di

import com.lingshu.core.data.llm.DeepSeekProvider
import com.lingshu.core.data.llm.ILlmProvider
import com.lingshu.core.data.llm.LlmRouter
import com.lingshu.core.data.llm.ModelProviderType
import com.lingshu.feature.chat.data.ChatRepository
import com.lingshu.feature.chat.data.TtsEngineImpl
import com.lingshu.feature.chat.data.prompt.IPromptAssembler
import com.lingshu.feature.chat.data.prompt.PromptAssembler
import com.lingshu.feature.chat.data.prompt.PromptInjector
import com.lingshu.core.common.event.IChatRepository
import com.lingshu.core.common.event.ITtsEngine
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ChatModule {

    @Binds
    @Singleton
    abstract fun bindChatRepository(
        chatRepository: ChatRepository
    ): IChatRepository

    @Binds
    @Singleton
    abstract fun bindTtsEngine(
        ttsEngine: TtsEngineImpl
    ): ITtsEngine

    @Binds
    @Singleton
    abstract fun bindPromptAssembler(
        promptAssembler: PromptAssembler
    ): IPromptAssembler

    companion object {

        @Provides
        @Singleton
        fun providePromptInjector(): PromptInjector {
            return PromptInjector()
        }

        @Provides
        @Singleton
        fun provideLlmRouter(
            deepSeekProvider: DeepSeekProvider
        ): LlmRouter {
            val providerMap = mapOf<ModelProviderType, ILlmProvider>(
                ModelProviderType.DEEPSEEK to deepSeekProvider
            )
            return object : LlmRouter {
                override fun resolve(type: ModelProviderType): ILlmProvider? {
                    return providerMap[type]
                }
            }
        }
    }
}
