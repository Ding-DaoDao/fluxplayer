package com.fluxplayer.app.core.datastore.datasource

import androidx.datastore.core.DataStore
import com.fluxplayer.app.core.common.Logger
import com.fluxplayer.app.core.model.PlayerPreferences
import javax.inject.Inject

class PlayerPreferencesDataSource @Inject constructor(
    private val preferencesDataStore: DataStore<PlayerPreferences>,
) : PreferencesDataSource<PlayerPreferences> {

    companion object {
        private const val TAG = "PlayerPreferencesDataSource"
    }

    override val preferences = preferencesDataStore.data

    /** 备份恢复必须向调用方报告写入失败，避免显示虚假的成功结果。 */
    suspend fun updateOrThrow(transform: suspend (PlayerPreferences) -> PlayerPreferences) {
        preferencesDataStore.updateData(transform)
    }

    override suspend fun update(transform: suspend (PlayerPreferences) -> PlayerPreferences) {
        try {
            preferencesDataStore.updateData(transform)
        } catch (ioException: Exception) {
            Logger.logError(TAG, "Failed to update app preferences: $ioException")
        }
    }
}
