package com.fluxplayer.app.core.tingshu

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** 由应用提供持久化的音频偏好，让在线听书与其他播放器共用同一设置。 */
interface ListeningAudioPreferencesProvider {
    val listeningRequireAudioFocus: Flow<Boolean>
    val listeningPauseOnHeadsetDisconnect: Flow<Boolean>
        get() = flowOf(true)
}
