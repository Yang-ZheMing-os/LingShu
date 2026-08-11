package com.lingshu.core.data.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonaDao {

    @Query("SELECT * FROM personas ORDER BY timestamp DESC")
    fun getAllPersonas(): Flow<List<PersonaEntity>>

    @Query("SELECT * FROM personas WHERE id = :id")
    fun getPersonaById(id: Long): Flow<PersonaEntity?>

    @Query("SELECT * FROM personas ORDER BY timestamp DESC LIMIT 1")
    fun getCurrentPersona(): Flow<PersonaEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPersona(persona: PersonaEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPersonas(personas: List<PersonaEntity>)

    @Update
    suspend fun updatePersona(persona: PersonaEntity)

    @Delete
    suspend fun deletePersona(persona: PersonaEntity)

    @Query("DELETE FROM personas")
    suspend fun deleteAllPersonas()

    @Query("SELECT COUNT(*) FROM personas")
    suspend fun getPersonaCount(): Int
}
