package com.lingshu.feature.update.di

import com.lingshu.feature.update.data.ErrorReportManager
import com.lingshu.feature.update.data.GitHubApi
import com.lingshu.feature.update.data.UpdateServiceImpl
import com.lingshu.feature.update.domain.IUpdateService
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class UpdateModule {

    @Binds
    @Singleton
    abstract fun bindUpdateService(
        updateServiceImpl: UpdateServiceImpl
    ): IUpdateService

    companion object {
        private const val GITHUB_API_BASE_URL = "https://api.github.com/"

        @Provides
        @Singleton
        @Named("github")
        fun provideGitHubOkHttpClient(): OkHttpClient {
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }

            return OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(loggingInterceptor)
                .build()
        }

        @Provides
        @Singleton
        fun provideGitHubApi(
            @Named("github") okHttpClient: OkHttpClient
        ): GitHubApi {
            return Retrofit.Builder()
                .baseUrl(GITHUB_API_BASE_URL)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(GitHubApi::class.java)
        }
    }
}
