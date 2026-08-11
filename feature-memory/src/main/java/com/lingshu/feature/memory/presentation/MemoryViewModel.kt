package com.lingshu.feature.memory.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lingshu.feature.memory.domain.IMemoryService
import com.lingshu.feature.memory.domain.Memory
import com.lingshu.feature.memory.domain.MemoryType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val memoryService: IMemoryService
) : ViewModel() {

    private val _selectedType = MutableStateFlow<MemoryType?>(null)
    val selectedType: StateFlow<MemoryType?> = _selectedType.asStateFlow()

    private val _searchKeyword = MutableStateFlow("")
    val searchKeyword: StateFlow<String> = _searchKeyword.asStateFlow()

    val shortTermMemories: StateFlow<List<Memory>> = memoryService.observeShortTerm()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val longTermMemories: StateFlow<List<Memory>> = memoryService.observeLongTerm()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun selectType(type: MemoryType?) {
        _selectedType.value = type
    }

    fun search(keyword: String) {
        _searchKeyword.value = keyword
    }

    fun deleteMemory(memoryId: Long) {
        viewModelScope.launch {
            memoryService.deleteLongTerm(memoryId)
        }
    }

    fun clearAllLongTerm() {
        viewModelScope.launch {
            memoryService.clearAllLongTerm()
        }
    }

    fun clearShortTerm() {
        viewModelScope.launch {
            memoryService.clearShortTerm()
        }
    }

    fun saveToLongTerm(memory: Memory) {
        viewModelScope.launch {
            memoryService.saveLongTerm(memory)
        }
    }

    fun filteredLongTermMemories(): List<Memory> {
        var result = longTermMemories.value
        
        _selectedType.value?.let { type ->
            result = result.filter { it.type == type }
        }
        
        val keyword = _searchKeyword.value
        if (keyword.isNotBlank()) {
            result = result.filter { 
                it.content.contains(keyword, ignoreCase = true)
            }
        }
        
        return result
    }
}
