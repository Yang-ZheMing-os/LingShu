package com.lingshu.feature.rag.di

import com.lingshu.feature.rag.data.IEmbeddingEngine
import com.lingshu.feature.rag.data.IEmbeddingGemma
import com.lingshu.feature.rag.data.MockEmbeddingEngine
import com.lingshu.feature.rag.data.RagServiceImpl
import com.lingshu.feature.rag.domain.IRagService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RagModule {

    @Provides
    @Singleton
    fun provideEmbeddingEngine(): IEmbeddingEngine {
        return MockEmbeddingEngine()
    }

    @Provides
    @Singleton
    fun provideRagService(
        embeddingEngine: IEmbeddingEngine
    ): IRagService {
        return RagServiceImpl(embeddingEngine)
    }
}
