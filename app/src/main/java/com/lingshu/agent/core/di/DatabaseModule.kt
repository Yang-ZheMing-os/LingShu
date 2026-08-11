package com.lingshu.agent.core.di

import android.content.Context
import androidx.room.Room
import com.lingshu.agent.core.database.Converters
import com.lingshu.agent.core.database.LingShuDatabase
import com.lingshu.agent.core.database.dao.ConversationDao
import com.lingshu.agent.core.database.dao.HealthDataDao
import com.lingshu.agent.core.database.dao.MessageDao
import com.lingshu.agent.core.database.dao.ModDao
import com.lingshu.agent.core.database.dao.PersonaDao
import com.lingshu.agent.core.database.dao.ClonedVoiceDao
import com.lingshu.agent.core.database.dao.MemoryDao
import com.lingshu.agent.core.database.dao.ScriptDao
import com.lingshu.agent.core.database.dao.CorrectionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private const val DATABASE_NAME = "lingshu_database.db"

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        converters: Converters
    ): LingShuDatabase {
        return Room.databaseBuilder(
            context,
            LingShuDatabase::class.java,
            DATABASE_NAME
        )
            .fallbackToDestructiveMigration()
            .addTypeConverter(converters)
            .build()
    }

    @Provides
    @Singleton
    fun provideMessageDao(database: LingShuDatabase): MessageDao = database.messageDao()

    @Provides
    @Singleton
    fun provideConversationDao(database: LingShuDatabase): ConversationDao = database.conversationDao()

    @Provides
    @Singleton
    fun providePersonaDao(database: LingShuDatabase): PersonaDao = database.personaDao()

    @Provides
    @Singleton
    fun provideModDao(database: LingShuDatabase): ModDao = database.modDao()

    @Provides
    @Singleton
    fun provideScriptDao(database: LingShuDatabase): ScriptDao = database.scriptDao()

    @Provides
    @Singleton
    fun provideHealthDataDao(database: LingShuDatabase): HealthDataDao = database.healthDataDao()

    @Provides
    @Singleton
    fun provideClonedVoiceDao(database: LingShuDatabase): ClonedVoiceDao = database.clonedVoiceDao()

    @Provides
    @Singleton
    fun provideMemoryDao(database: LingShuDatabase): MemoryDao = database.memoryDao()

    @Provides
    @Singleton
    fun provideCorrectionDao(database: LingShuDatabase): CorrectionDao = database.correctionDao()
}
