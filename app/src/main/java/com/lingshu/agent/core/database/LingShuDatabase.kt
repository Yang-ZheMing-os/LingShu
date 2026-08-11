package com.lingshu.agent.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.lingshu.agent.core.database.dao.ConversationDao
import com.lingshu.agent.core.database.dao.HealthDataDao
import com.lingshu.agent.core.database.dao.MessageDao
import com.lingshu.agent.core.database.dao.ModDao
import com.lingshu.agent.core.database.dao.PersonaDao
import com.lingshu.agent.core.database.dao.ClonedVoiceDao
import com.lingshu.agent.core.database.dao.MemoryDao
import com.lingshu.agent.core.database.dao.ScriptDao
import com.lingshu.agent.core.database.dao.CorrectionDao
import com.lingshu.agent.core.database.entity.ClonedVoiceEntity
import com.lingshu.agent.core.database.entity.CorrectionEntity
import com.lingshu.agent.core.database.entity.MemoryEntity
import com.lingshu.agent.core.database.entity.ConversationEntity
import com.lingshu.agent.core.database.entity.HealthDataEntity
import com.lingshu.agent.core.database.entity.MessageEntity
import com.lingshu.agent.core.database.entity.ModEntity
import com.lingshu.agent.core.database.entity.PersonaEntity
import com.lingshu.agent.core.database.entity.ScriptEntity

@Database(
    entities = [
        MessageEntity::class,
        ConversationEntity::class,
        PersonaEntity::class,
        ModEntity::class,
        ScriptEntity::class,
        HealthDataEntity::class,
        ClonedVoiceEntity::class,
        MemoryEntity::class,
        CorrectionEntity::class
    ],
    version = 6,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class LingShuDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun personaDao(): PersonaDao
    abstract fun modDao(): ModDao
    abstract fun scriptDao(): ScriptDao
    abstract fun healthDataDao(): HealthDataDao
    abstract fun clonedVoiceDao(): ClonedVoiceDao
    abstract fun memoryDao(): MemoryDao
    abstract fun correctionDao(): CorrectionDao
}
