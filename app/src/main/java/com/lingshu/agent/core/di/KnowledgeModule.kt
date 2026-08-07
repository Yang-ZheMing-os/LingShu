package com.lingshu.agent.core.di

import com.lingshu.agent.feature.knowledge.InMemoryVectorStore
import com.lingshu.agent.feature.knowledge.VectorStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class KnowledgeModule {

    @Binds
    @Singleton
    abstract fun bindVectorStore(impl: InMemoryVectorStore): VectorStore
}
