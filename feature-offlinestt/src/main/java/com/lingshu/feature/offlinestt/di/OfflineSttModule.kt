package com.lingshu.feature.offlinestt.di

// ================================================================================
// settings.gradle.kts 末尾追加 include（请手动复制到项目根 settings.gradle.kts）:
//
// include(":feature-offlinestt")
// include(":feature-offlinetts")
// ================================================================================

import com.lingshu.feature.offlinestt.data.OfflineSttRouter
import com.lingshu.feature.offlinestt.domain.IOfflineSttEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class OfflineSttModule {

    @Binds
    @Singleton
    abstract fun bindOfflineSttEngine(
        router: OfflineSttRouter
    ): IOfflineSttEngine
}
