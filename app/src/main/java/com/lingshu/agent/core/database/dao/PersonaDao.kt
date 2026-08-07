package com.lingshu.agent.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.lingshu.agent.core.database.entity.PersonaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonaDao {

    @Upsert
    suspend fun upsert(persona: PersonaEntity)

    @Upsert
    suspend fun upsertAll(personas: List<PersonaEntity>)

    @Query("DELETE FROM personas WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM personas ORDER BY isActive DESC, updatedAt DESC")
    fun observeAll(): Flow<List<PersonaEntity>>

    @Query("SELECT * FROM personas WHERE isActive = 1 LIMIT 1")
    fun observeActive(): Flow<PersonaEntity?>

    @Query("SELECT * FROM personas WHERE isDefault = :isSystem ORDER BY isActive DESC, updatedAt DESC")
    fun observeBySystem(isSystem: Boolean): Flow<List<PersonaEntity>>

    @Query("SELECT * FROM personas WHERE (:tag = '' OR tags LIKE '%' || :tag || '%') AND (name LIKE '%' || :keyword || '%' OR systemPrompt LIKE '%' || :keyword || '%') ORDER BY isActive DESC, updatedAt DESC")
    fun search(tag: String, keyword: String): Flow<List<PersonaEntity>>

    @Query("SELECT * FROM personas WHERE id = :id")
    suspend fun getById(id: String): PersonaEntity?

    @Query("SELECT * FROM personas WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveSuspend(): PersonaEntity?

    @Query("UPDATE personas SET isActive = CASE WHEN id = :id THEN 1 ELSE 0 END")
    suspend fun setActive(id: String)

    @Query("UPDATE personas SET memory = :memory, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateMemory(id: String, memory: String, updatedAt: Long)

    @Query("UPDATE personas SET traitsOpenness = :openness, traitsConscientiousness = :conscientiousness, traitsExtraversion = :extraversion, traitsAgreeableness = :agreeableness, traitsNeuroticism = :neuroticism, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTraits(id: String, openness: Double, conscientiousness: Double, extraversion: Double, agreeableness: Double, neuroticism: Double, updatedAt: Long)

    @Query("SELECT COUNT(*) FROM personas")
    suspend fun count(): Int
}
