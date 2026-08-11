package com.lingshu.feature.memory.di

import com.lingshu.core.data.database.MemoryDao
import com.lingshu.feature.memory.data.MemoryExtractor
import com.lingshu.feature.memory.data.MemoryServiceImpl
import com.lingshu.feature.memory.domain.IMemoryService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MemoryModule {

    @Provides
    @Singleton
    fun provideMemoryExtractor(): MemoryExtractor {
        return MemoryExtractor()
    }

    @Provides
    @Singleton
    fun provideMemoryService(
        memoryDao: MemoryDao,
        memoryExtractor: MemoryExtractor
    ): IMemoryService {
        return MemoryServiceImpl(memoryDao, memoryExtractor)
    }
}
