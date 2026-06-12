package com.fluxplayer.app.feature.videopicker.screens.mediapicker

import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.fluxplayer.app.core.common.extensions.prettyName
import com.fluxplayer.app.core.data.repository.PlaybackHistoryRepository
import com.fluxplayer.app.core.data.repository.PreferencesRepository
import com.fluxplayer.app.core.domain.GetSortedMediaUseCase
import com.fluxplayer.app.core.media.services.MediaService
import com.fluxplayer.app.core.media.sync.MediaInfoSynchronizer
import com.fluxplayer.app.core.media.sync.MediaSynchronizer
import com.fluxplayer.app.core.model.ApplicationPreferences
import com.fluxplayer.app.core.model.Folder
import com.fluxplayer.app.core.ui.base.DataState
import com.fluxplayer.app.feature.videopicker.navigation.FolderArgs
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class MediaPickerViewModel @Inject constructor(
    getSortedMediaUseCase: GetSortedMediaUseCase,
    savedStateHandle: SavedStateHandle,
    private val mediaService: MediaService,
    private val preferencesRepository: PreferencesRepository,
    private val playbackHistoryRepository: PlaybackHistoryRepository,
    private val mediaInfoSynchronizer: MediaInfoSynchronizer,
    private val mediaSynchronizer: MediaSynchronizer,
) : ViewModel() {

    private val folderArgs = FolderArgs(savedStateHandle)

    val folderPath = folderArgs.folderId

    private val uiStateInternal = MutableStateFlow(
        MediaPickerUiState(
            folderName = folderPath?.let { File(folderPath).prettyName },
            preferences = preferencesRepository.applicationPreferences.value,
        ),
    )
    val uiState = uiStateInternal.asStateFlow()

    init {
        viewModelScope.launch {
            playbackHistoryRepository.getHistoryFlow().collect { history ->
                val historyUris = history.map { it.uriString }.toSet()
                // 更新已播放 URI 集合（供足迹视觉标识使用）
                uiStateInternal.update { currentState ->
                    currentState.copy(playedUriSet = historyUris)
                }
                // 清除已删除历史对应的足迹（仅清理视频类 URI）
                preferencesRepository.updateApplicationPreferences { prefs ->
                    val cleaned = prefs.latestFootprintPerDir.filterValues { value ->
                        !value.startsWith("http") || value in historyUris
                    }
                    if (cleaned.size == prefs.latestFootprintPerDir.size) prefs
                    else prefs.copy(latestFootprintPerDir = cleaned)
                }
            }
        }
        viewModelScope.launch {
            getSortedMediaUseCase.invoke(folderPath).collect {
                uiStateInternal.update { currentState ->
                    currentState.copy(
                        mediaDataState = DataState.Success(it),
                    )
                }
            }
        }

        viewModelScope.launch {
            preferencesRepository.applicationPreferences.collect {
                uiStateInternal.update { currentState ->
                    currentState.copy(
                        preferences = it,
                    )
                }
            }
        }
    }

    /** 记录足迹：同目录下只保留最新的一个 */
    fun recordFootprint(parentDir: String, itemPath: String) {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences { prefs ->
                prefs.copy(
                    latestFootprintPerDir = prefs.latestFootprintPerDir + (parentDir to itemPath),
                )
            }
        }
    }

    fun onEvent(event: MediaPickerUiEvent) {
        when (event) {
            is MediaPickerUiEvent.DeleteFolders -> deleteFolders(event.folders)
            is MediaPickerUiEvent.DeleteVideos -> deleteVideos(event.videos)
            is MediaPickerUiEvent.ShareVideos -> shareVideos(event.videos)
            is MediaPickerUiEvent.Refresh -> refresh()
            is MediaPickerUiEvent.RenameVideo -> renameVideo(event.uri, event.to)
            is MediaPickerUiEvent.AddToSync -> addToMediaInfoSynchronizer(event.uri)
            is MediaPickerUiEvent.UpdateMenu -> updateMenu(event.preferences)
        }
    }

    private fun deleteFolders(folders: List<Folder>) {
        viewModelScope.launch {
            val uris = folders.flatMap { folder ->
                folder.allMediaList.map { video ->
                    video.uriString.toUri()
                }
            }
            mediaService.deleteMedia(uris)
        }
    }

    private fun deleteVideos(uris: List<String>) {
        viewModelScope.launch {
            mediaService.deleteMedia(uris.map { it.toUri() })
        }
    }

    private fun shareVideos(uris: List<String>) {
        viewModelScope.launch {
            mediaService.shareMedia(uris.map { it.toUri() })
        }
    }

    private fun addToMediaInfoSynchronizer(uri: Uri) {
        viewModelScope.launch {
            mediaInfoSynchronizer.sync(uri)
        }
    }

    private fun renameVideo(uri: Uri, to: String) {
        viewModelScope.launch {
            mediaService.renameMedia(uri, to)
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            uiStateInternal.update { it.copy(refreshing = true) }
            mediaSynchronizer.refresh()
            uiStateInternal.update { it.copy(refreshing = false) }
        }
    }

    private fun updateMenu(preferences: ApplicationPreferences) {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences { preferences }
        }
    }
}

@Stable
data class MediaPickerUiState(
    val folderName: String?,
    val mediaDataState: DataState<Folder?> = DataState.Loading,
    val refreshing: Boolean = false,
    val preferences: ApplicationPreferences = ApplicationPreferences(),
    val playedUriSet: Set<String> = emptySet(),
)

sealed interface MediaPickerUiEvent {
    data class DeleteVideos(val videos: List<String>) : MediaPickerUiEvent
    data class DeleteFolders(val folders: List<Folder>) : MediaPickerUiEvent
    data class ShareVideos(val videos: List<String>) : MediaPickerUiEvent
    data object Refresh : MediaPickerUiEvent
    data class RenameVideo(val uri: Uri, val to: String) : MediaPickerUiEvent
    data class AddToSync(val uri: Uri) : MediaPickerUiEvent
    data class UpdateMenu(val preferences: ApplicationPreferences) : MediaPickerUiEvent
}
