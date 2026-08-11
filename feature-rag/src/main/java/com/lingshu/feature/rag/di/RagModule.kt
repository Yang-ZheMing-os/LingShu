package com.lingshu.feature.rag.di

import com.lingshu.feature.rag.data.HashBasedEmbeddingEngine
import com.lingshu.feature.rag.data.IEmbeddingEngine
import com.lingshu.feature.rag.data.IEmbeddingGemma
import com.lingshu.feature.rag.data.RagServiceImpl
import com.lingshu.feature.rag.domain.IRagService
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RagModule {

    @Binds
    @Singleton
    abstract fun bindEmbeddingEngine(
        engine: HashBasedEmbeddingEngine
    ): IEmbeddingEngine

    @Binds
    @Singleton
    abstract fun bindRagService(
        impl: RagServiceImpl
    ): IRagService
}
