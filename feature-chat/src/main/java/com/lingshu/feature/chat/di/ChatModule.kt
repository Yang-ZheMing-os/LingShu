package com.lingshu.feature.chat.di

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
    }
}
