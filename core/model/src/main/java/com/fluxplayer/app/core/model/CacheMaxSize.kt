package com.fluxplayer.app.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class CacheMaxSize {
    GB_2,
    GB_5,
    GB_10,
    GB_20,
    GB_50,
    UNLIMITED,
    ;

    val bytes: Long
        get() = when (this) {
            GB_2 -> 2L * 1024 * 1024 * 1024
            GB_5 -> 5L * 1024 * 1024 * 1024
            GB_10 -> 10L * 1024 * 1024 * 1024
            GB_20 -> 20L * 1024 * 1024 * 1024
            GB_50 -> 50L * 1024 * 1024 * 1024
            UNLIMITED -> Long.MAX_VALUE
        }
}
