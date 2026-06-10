package dev.anilbeesetti.nextplayer.feature.player

import dev.anilbeesetti.nextplayer.core.model.AnimeMatch
import dev.anilbeesetti.nextplayer.core.model.DanmakuSource
import dev.anilbeesetti.nextplayer.core.model.EpisodeInfo
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
