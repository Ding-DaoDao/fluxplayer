package com.fluxplayer.app.feature.player

import com.fluxplayer.app.core.model.AnimeMatch
import com.fluxplayer.app.core.model.DanmakuSource
import com.fluxplayer.app.core.model.EpisodeInfo
import java.io.File

sealed interface DanmakuSelectionContext {
    data class LocalFile(
        val dir: File,
        val currentFile: File,
        val allFiles: List<File>,
    ) : DanmakuSelectionContext

    data class Network(
        val source: DanmakuSource,
        val anime: AnimeMatch,
        val currentEpisode: EpisodeInfo,
        val episodes: List<EpisodeInfo>,
    ) : DanmakuSelectionContext
}
