package com.fluxplayer.app.core.datastore.datasource

import androidx.datastore.core.DataStore
import com.fluxplayer.app.core.common.Logger
import com.fluxplayer.app.core.model.ApplicationPreferences
import javax.inject.Inject

class AppPreferencesDataSource @Inject constructor(
    private val appPreferences: DataStore<ApplicationPreferences>,
) : PreferencesDataSource<ApplicationPreferences> {

    companion object {
        private const val TAG = "AppPreferencesDataSource"
    }

    override val preferences = appPreferences.data

    override suspend fun update(
        transform: suspend (ApplicationPreferences) -> ApplicationPreferences,
    ) {
        try {
            appPreferences.updateData(transform)
        } catch (ioException: Exception) {
            Logger.logError(TAG, "Failed to update app preferences: $ioException")
        }
    }
}
