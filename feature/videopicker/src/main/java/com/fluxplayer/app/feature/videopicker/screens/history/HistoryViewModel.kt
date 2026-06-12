package com.fluxplayer.app.feature.videopicker.screens.history

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.fluxplayer.app.core.data.repository.PlaybackHistoryRepository
import com.fluxplayer.app.core.data.repository.PreferencesRepository
import com.fluxplayer.app.core.model.PlaybackHistory
import com.fluxplayer.app.feature.videopicker.CloudDirectoryCache
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HistoryUiState(
    val historyList: List<PlaybackHistory> = emptyList(),
    val isLoading: Boolean = true,
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val playbackHistoryRepository: PlaybackHistoryRepository,
    private val preferencesRepository: PreferencesRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            playbackHistoryRepository.getHistoryFlow().collect { historyList ->
                // 按 parentPath 分组，每组只保留最新一条
                val latestPerDir = historyList
                    .filter { it.parentPath != null }
                    .groupBy { it.parentPath!! }
                    .mapValues { (_, list) -> list.maxByOrNull { it.lastPlayedTime }!! }
                    .values.sortedByDescending { it.lastPlayedTime }
                _uiState.value = HistoryUiState(
                    historyList = latestPerDir,
                    isLoading = false,
                )
            }
        }
    }

    fun deleteItem(uriString: String) {
        viewModelScope.launch {
            playbackHistoryRepository.deleteItem(uriString)
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            playbackHistoryRepository.clearAll()
            // 同时清除所有足迹
            preferencesRepository.updateApplicationPreferences { prefs ->
                prefs.copy(latestFootprintPerDir = emptyMap())
            }
            // 清除云盘目录缓存
            CloudDirectoryCache.clearAll(context)
        }
    }
}
