package com.fluxplayer.app.feature.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 本地听书服务发布的实时状态，离开播放页后仍持续更新。 */
data class LocalAudiobookPlaybackState(
    val bookPath: String = "",
    val title: String = "",
    val chapterUri: String = "",
    val chapterTitle: String = "",
    val coverUri: String? = null,
    val index: Int = 0,
    val position: Long = 0,
    val duration: Long = 0,
    val playing: Boolean = false,
    val playWhenReady: Boolean = false,
    val loading: Boolean = false,
    val lastPlayedAt: Long = 0,
)

object LocalAudiobookPlayback {
    internal val mutableState = MutableStateFlow(LocalAudiobookPlaybackState())
    val state = mutableState.asStateFlow()
}
