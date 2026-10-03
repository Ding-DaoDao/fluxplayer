package com.github.eprendre.tingshu.utils

import com.fluxplayer.app.core.tingshu.SourceHost

object ExternalSourcePrefs {
    fun putString(key: String, value: String?) = SourceHost.putString(key, value)

    fun getString(key: String, defValue: String?): String? = SourceHost.getString(key, defValue)
}
