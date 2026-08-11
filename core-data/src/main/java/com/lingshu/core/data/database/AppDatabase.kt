package com.lingshu.core.data.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        MessageEntity::class,
        MemoryEntity::class,
        PersonaEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao

    abstract fun memoryDao(): MemoryDao

    abstract fun personaDao(): PersonaDao

    companion object {
        const val DATABASE_NAME = "lingshu_db"
    }
}
