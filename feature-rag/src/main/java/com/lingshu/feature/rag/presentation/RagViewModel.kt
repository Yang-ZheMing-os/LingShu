package com.lingshu.feature.rag.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lingshu.core.common.error.Result
import com.lingshu.core.common.state.UiState
import com.lingshu.feature.rag.domain.Chunk
import com.lingshu.feature.rag.domain.Document
import com.lingshu.feature.rag.domain.IRagService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class RagViewModel @Inject constructor(
    private val ragService: IRagService
) : ViewModel() {

    private val _documents = MutableStateFlow<List<Document>>(emptyList())
    val documents: StateFlow<List<Document>> = _documents.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Chunk>>(emptyList())
    val searchResults: StateFlow<List<Chunk>> = _searchResults.asStateFlow()

    private val _askState = MutableStateFlow<UiState<String>>(UiState.Idle)
    val askState: StateFlow<UiState<String>> = _askState.asStateFlow()

    private val _uploadState = MutableStateFlow<UiState<Unit>>(UiState.Idle)
    val uploadState: StateFlow<UiState<Unit>> = _uploadState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _askQuery = MutableStateFlow("")
    val askQuery: StateFlow<String> = _askQuery.asStateFlow()

    private val _selectedTab = MutableStateFlow(RagTab.DOCUMENTS)
    val selectedTab: StateFlow<RagTab> = _selectedTab.asStateFlow()

    init {
        loadDocuments()
    }

    private fun loadDocuments() {
        _documents.value = ragService.listDocuments()
    }

    fun selectTab(tab: RagTab) {
        _selectedTab.value = tab
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun updateAskQuery(query: String) {
        _askQuery.value = query
    }

    fun uploadDocument(file: File) {
        viewModelScope.launch {
            _uploadState.value = UiState.Loading
            val result = ragService.uploadDocument(file)
            when (result) {
                is Result.Success -> {
                    _uploadState.value = UiState.Success(Unit)
                    loadDocuments()
                }
                is Result.Error -> {
                    _uploadState.value = UiState.Error(result.exception, result.code)
                }
            }
        }
    }

    fun search(query: String) {
        viewModelScope.launch {
            val result = ragService.search(query)
            when (result) {
                is Result.Success -> {
                    _searchResults.value = result.data
                }
                is Result.Error -> {
                    _searchResults.value = emptyList()
                }
            }
        }
    }

    fun ask(query: String) {
        viewModelScope.launch {
            _askState.value = UiState.Loading
            val result = ragService.ask(query)
            when (result) {
                is Result.Success -> {
                    _askState.value = UiState.Success(result.data)
                }
                is Result.Error -> {
                    _askState.value = UiState.Error(result.exception, result.code)
                }
            }
        }
    }

    fun deleteDocument(documentId: String) {
        viewModelScope.launch {
            val result = ragService.deleteDocument(documentId)
            if (result.isSuccess()) {
                loadDocuments()
            }
        }
    }

    fun resetUploadState() {
        _uploadState.value = UiState.Idle
    }
}

enum class RagTab {
    DOCUMENTS,
    CHAT
}
