package dev.anilbeesetti.nextplayer.feature.videopicker.screens.history

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.anilbeesetti.nextplayer.core.data.repository.PlaybackHistoryRepository
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.model.PlaybackHistory
import dev.anilbeesetti.nextplayer.feature.videopicker.CloudDirectoryCache
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
                _uiState.value = HistoryUiState(
                    historyList = historyList,
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
