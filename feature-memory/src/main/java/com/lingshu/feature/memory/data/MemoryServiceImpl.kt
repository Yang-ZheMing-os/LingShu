package com.lingshu.feature.memory.data

import com.lingshu.core.common.log.LingShuLog
import com.lingshu.core.data.database.MemoryDao
import com.lingshu.core.data.database.MemoryEntity
import com.lingshu.feature.memory.domain.IMemoryService
import com.lingshu.feature.memory.domain.Memory
import com.lingshu.feature.memory.domain.MemoryType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class MemoryServiceImpl @Inject constructor(
    private val memoryDao: MemoryDao,
    private val memoryExtractor: MemoryExtractor
) : IMemoryService {

    private val _shortTermMemories = MutableStateFlow<List<Memory>>(emptyList())
    override fun observeShortTerm(): Flow<List<Memory>> = _shortTermMemories.asStateFlow()

    private val maxShortTermSize = 20

    override suspend fun getShortTerm(): List<Memory> {
        return _shortTermMemories.value
    }

    override suspend fun addShortTerm(memory: Memory) {
        val currentList = _shortTermMemories.value.toMutableList()
        currentList.add(0, memory)
        if (currentList.size > maxShortTermSize) {
            val removed = currentList.removeAt(currentList.size - 1)
            LingShuLog.d(TAG, "短期记忆已满，移除最旧记忆: ${removed.content.take(30)}...")
        }
        _shortTermMemories.value = currentList
        LingShuLog.d(TAG, "添加短期记忆: ${memory.content.take(30)}...")
    }

    override suspend fun clearShortTerm() {
        _shortTermMemories.value = emptyList()
        LingShuLog.i(TAG, "清空短期记忆")
    }

    override fun observeLongTerm(): Flow<List<Memory>> {
        return memoryDao.getAllMemories().map { entities ->
            entities.map { it.toMemory() }
        }
    }

    override suspend fun saveLongTerm(memory: Memory) {
        try {
            val entity = memory.toEntity()
            memoryDao.insertMemory(entity)
            LingShuLog.d(TAG, "保存长期记忆: ${memory.content.take(30)}...")
        } catch (e: Exception) {
            LingShuLog.e(TAG, "保存长期记忆失败", e)
        }
    }

    override suspend fun getLongTerm(): List<Memory> {
        return try {
            val entities = memoryDao.getAllMemories().first()
            entities.map { it.toMemory() }
        } catch (e: Exception) {
            LingShuLog.e(TAG, "获取长期记忆失败", e)
            emptyList()
        }
    }

    override suspend fun searchLongTerm(keyword: String): List<Memory> {
        return try {
            val allMemories = getLongTerm()
            allMemories.filter { memory ->
                memory.content.contains(keyword, ignoreCase = true)
            }
        } catch (e: Exception) {
            LingShuLog.e(TAG, "搜索长期记忆失败", e)
            emptyList()
        }
    }

    override suspend fun deleteLongTerm(memoryId: Long) {
        try {
            val entity = MemoryEntity(
                id = memoryId,
                content = "",
                type = "",
                confidence = 0f
            )
            memoryDao.deleteMemory(entity)
            LingShuLog.d(TAG, "删除长期记忆: id=$memoryId")
        } catch (e: Exception) {
            LingShuLog.e(TAG, "删除长期记忆失败", e)
        }
    }

    override suspend fun clearAllLongTerm() {
        try {
            memoryDao.deleteAllMemories()
            LingShuLog.i(TAG, "清空所有长期记忆")
        } catch (e: Exception) {
            LingShuLog.e(TAG, "清空长期记忆失败", e)
        }
    }

    override suspend fun extractFromDialogue(userInput: String, aiResponse: String): List<Memory> {
        val extractedMemories = memoryExtractor.extract(userInput, aiResponse)
        LingShuLog.d(TAG, "从对话中抽取到 ${extractedMemories.size} 条记忆")
        
        extractedMemories.forEach { memory ->
            addShortTerm(memory)
        }
        
        return extractedMemories
    }

    override suspend fun buildContextPrompt(): String {
        val shortTerm = getShortTerm()
        val longTerm = getLongTerm()
        
        val stringBuilder = StringBuilder()
        
        if (longTerm.isNotEmpty()) {
            stringBuilder.append("【关于用户的长期记忆】\n")
            longTerm.take(10).forEachIndexed { index, memory ->
                stringBuilder.append("${index + 1}. ${memory.content}\n")
            }
            stringBuilder.append("\n")
        }
        
        if (shortTerm.isNotEmpty()) {
            stringBuilder.append("【最近对话摘要】\n")
            shortTerm.take(10).forEachIndexed { index, memory ->
                stringBuilder.append("${index + 1}. ${memory.content}\n")
            }
            stringBuilder.append("\n")
        }
        
        val prompt = stringBuilder.toString().ifBlank { "" }
        LingShuLog.d(TAG, "构建上下文提示，长度: ${prompt.length}")
        return prompt
    }

    private fun MemoryEntity.toMemory(): Memory {
        return Memory(
            id = this.id,
            content = this.content,
            type = try {
                MemoryType.valueOf(this.type)
            } catch (e: IllegalArgumentException) {
                MemoryType.FACT
            },
            source = "database",
            createdAt = this.timestamp,
            updatedAt = this.timestamp,
            importance = (this.confidence * 10).toInt().coerceIn(1, 10)
        )
    }

    private fun Memory.toEntity(): MemoryEntity {
        return MemoryEntity(
            id = this.id,
            content = this.content,
            type = this.type.name,
            confidence = this.importance / 10f,
            timestamp = this.createdAt
        )
    }

    companion object {
        private const val TAG = "MemoryService"
    }
}
