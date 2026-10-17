package com.fluxplayer.app.feature.player

/** App playback services call this on the main thread, independently of system audio focus. */
class PlaybackCoordinator {
    private var owner: Any? = null
    private var pauseOwner: (() -> Unit)? = null

    fun claim(requester: Any, pause: () -> Unit) {
        val pausePrevious = pauseOwner.takeIf { owner !== requester }
        owner = requester
        pauseOwner = pause
        pausePrevious?.invoke()
    }

    fun release(requester: Any) {
        if (owner === requester) {
            owner = null
            pauseOwner = null
        }
    }

    companion object {
        val shared = PlaybackCoordinator()
    }
}
