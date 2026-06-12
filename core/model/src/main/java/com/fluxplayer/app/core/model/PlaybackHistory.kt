package com.fluxplayer.app.core.model

data class PlaybackHistory(
    val uriString: String,
    val title: String,
    val source: VideoSource,
    val lastPlayedTime: Long,
    val playbackPosition: Long,
    val duration: Long,
    val originalUriString: String? = null,
    val thumbnailPath: String? = null,
    val parentPath: String? = null,
) {
    val playedPercentage: Float
        get() = if (duration > 0) {
            (playbackPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}
