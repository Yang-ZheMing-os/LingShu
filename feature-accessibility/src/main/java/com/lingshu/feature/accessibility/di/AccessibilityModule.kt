package com.lingshu.feature.accessibility.di

import com.lingshu.feature.accessibility.data.AccessibilityControlImpl
import com.lingshu.feature.accessibility.domain.IAccessibilityControl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AccessibilityModule {

    @Binds
    @Singleton
    abstract fun bindAccessibilityControl(
        accessibilityControlImpl: AccessibilityControlImpl
    ): IAccessibilityControl
}
