package com.lingshu.feature.guide.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lingshu.core.data.datastore.AppPreferences
import com.lingshu.feature.guide.data.GuidePage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GuideViewModel @Inject constructor(
    private val appPreferences: AppPreferences
) : ViewModel() {

    private val _currentPage = MutableStateFlow(0)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    private val _isFirstLaunch = MutableStateFlow(true)
    val isFirstLaunch: StateFlow<Boolean> = _isFirstLaunch.asStateFlow()

    private val _shouldShowPermissionQueue = MutableStateFlow(false)
    val shouldShowPermissionQueue: StateFlow<Boolean> = _shouldShowPermissionQueue.asStateFlow()

    init {
        viewModelScope.launch {
            appPreferences.isFirstLaunch.collect { isFirst ->
                _isFirstLaunch.value = isFirst
            }
        }
    }

    fun onPageChanged(page: Int) {
        _currentPage.value = page.coerceIn(0, GuidePage.pageCount - 1)
    }

    fun nextPage() {
        if (_currentPage.value < GuidePage.pageCount - 1) {
            _currentPage.value++
        }
    }

    fun isLastPage(): Boolean {
        return _currentPage.value >= GuidePage.pageCount - 1
    }

    fun markGuideComplete() {
        viewModelScope.launch {
            appPreferences.setFirstLaunch(false)
            _shouldShowPermissionQueue.value = true
        }
    }

    fun onPermissionQueueComplete() {
        _shouldShowPermissionQueue.value = false
    }
}
