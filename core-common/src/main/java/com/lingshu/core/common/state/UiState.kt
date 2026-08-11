package com.lingshu.core.common.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class UiState<out T> {
    object Idle : UiState<Nothing>()
    object Loading : UiState<Nothing>()
    data class Success<out T>(val data: T) : UiState<T>()
    data class Error(val code: String, val message: String) : UiState<Nothing>()

    val isIdle: Boolean get() = this is Idle
    val isLoading: Boolean get() = this is Loading
    val isSuccess: Boolean get() = this is Success
    val isError: Boolean get() = this is Error

    fun getOrNull(): T? = (this as? Success)?.data
}

class UiStateHolder<T>(initialValue: UiState<T> = UiState.Idle) {
    private val _state = MutableStateFlow<UiState<T>>(initialValue)
    val state: StateFlow<UiState<T>> = _state.asStateFlow()

    fun setIdle() { _state.value = UiState.Idle }
    fun setLoading() { _state.value = UiState.Loading }
    fun setSuccess(data: T) { _state.value = UiState.Success(data) }
    fun setError(code: String, message: String) { _state.value = UiState.Error(code, message) }
}
