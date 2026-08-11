package com.lingshu.feature.offlinetts.di

// ================================================================================
// settings.gradle.kts 末尾追加 include（请手动复制到项目根 settings.gradle.kts）:
//
// include(":feature-offlinestt")
// include(":feature-offlinetts")
// ================================================================================

import com.lingshu.feature.offlinetts.data.OfflineTtsRouter
import com.lingshu.feature.offlinetts.domain.IOfflineTtsEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class OfflineTtsModule {

    @Binds
    @Singleton
    abstract fun bindOfflineTtsEngine(
        router: OfflineTtsRouter
    ): IOfflineTtsEngine
}
