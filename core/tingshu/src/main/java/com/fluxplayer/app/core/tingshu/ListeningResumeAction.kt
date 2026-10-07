package com.fluxplayer.app.core.tingshu

enum class ListeningResumeAction { Restore, Replay, Pause, PrepareAndPlay, Play }

/** 没有会话或播放列表时必须重建播放，不能只发送播放指令。 */
fun listeningResumeAction(hasSession: Boolean, hasMedia: Boolean, ended: Boolean, idle: Boolean, playWhenReady: Boolean): ListeningResumeAction = when {
    !hasSession || !hasMedia -> ListeningResumeAction.Restore
    ended -> ListeningResumeAction.Replay
    idle -> ListeningResumeAction.PrepareAndPlay
    playWhenReady -> ListeningResumeAction.Pause
    else -> ListeningResumeAction.Play
}
