package com.lingshu.feature.memory.domain

import kotlinx.coroutines.flow.Flow

interface IMemoryService {
    fun observeShortTerm(): Flow<List<Memory>>
    suspend fun getShortTerm(): List<Memory>
    suspend fun addShortTerm(memory: Memory)
    suspend fun clearShortTerm()
    fun observeLongTerm(): Flow<List<Memory>>
    suspend fun saveLongTerm(memory: Memory)
    suspend fun getLongTerm(): List<Memory>
    suspend fun searchLongTerm(keyword: String): List<Memory>
    suspend fun deleteLongTerm(memoryId: Long)
    suspend fun clearAllLongTerm()
    suspend fun extractFromDialogue(userInput: String, aiResponse: String): List<Memory>
    suspend fun buildContextPrompt(): String
}
