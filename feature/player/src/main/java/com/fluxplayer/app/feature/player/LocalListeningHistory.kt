package com.fluxplayer.app.feature.player

import android.content.Context
import com.fluxplayer.app.core.data.repository.AudiobookProgressRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

object LocalListeningHistory {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun audiobookProgressRepository(): AudiobookProgressRepository
    }

    suspend fun clear(context: Context, bookPath: String? = null, now: Long = System.currentTimeMillis()) {
        val repository = EntryPointAccessors.fromApplication(context.applicationContext, Dependencies::class.java).audiobookProgressRepository()
        repository.clearHistory(bookPath, now)
    }
}
