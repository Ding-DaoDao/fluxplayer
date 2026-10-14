package com.fluxplayer.app.core.data.repository

import com.fluxplayer.app.core.model.ApplicationPreferences
import com.fluxplayer.app.core.model.PlayerPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface PreferencesRepository {

    /**
     * Stream of [ApplicationPreferences].
     */
    val applicationPreferences: StateFlow<ApplicationPreferences>

    /**
     * Stream of [PlayerPreferences].
     */
    val playerPreferences: StateFlow<PlayerPreferences>

    /** 首次订阅等待磁盘偏好加载，避免把临时默认值当成用户选择。 */
    val persistedApplicationPreferences: Flow<ApplicationPreferences>
        get() = applicationPreferences

    /** 播放引擎初始化必须使用已持久化的参数。 */
    val persistedPlayerPreferences: Flow<PlayerPreferences>
        get() = playerPreferences

    suspend fun updateApplicationPreferences(
        transform: suspend (ApplicationPreferences) -> ApplicationPreferences,
    )

    suspend fun updatePlayerPreferences(transform: suspend (PlayerPreferences) -> PlayerPreferences)

    suspend fun resetPreferences()
}
