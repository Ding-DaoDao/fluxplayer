package com.fluxplayer.app.settings.extensions

import androidx.compose.runtime.Composable
import com.fluxplayer.app.core.model.CacheMaxSize

@Composable
fun CacheMaxSize.name(): String {
    return when (this) {
        CacheMaxSize.GB_2 -> "2 GB"
        CacheMaxSize.GB_5 -> "5 GB"
        CacheMaxSize.GB_10 -> "10 GB"
        CacheMaxSize.GB_20 -> "20 GB"
        CacheMaxSize.GB_50 -> "50 GB"
        CacheMaxSize.UNLIMITED -> "无限制"
    }
}
