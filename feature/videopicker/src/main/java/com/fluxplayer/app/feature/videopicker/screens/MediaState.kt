package com.fluxplayer.app.feature.videopicker.screens

import com.fluxplayer.app.core.model.Folder

sealed interface MediaState {
    data object Loading : MediaState
    data class Success(val data: Folder?) : MediaState
}
