package dev.anilbeesetti.nextplayer.feature.player.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember

class IntroOutroState {
    private val timestamps = mutableStateMapOf<String, IntroOutroTimestamps>()

    fun getTimestamps(key: String): IntroOutroTimestamps {
        return timestamps[key] ?: IntroOutroTimestamps()
    }

    fun setIntro(key: String, ms: Long) {
        val existing = timestamps[key]
        timestamps[key] = (existing ?: IntroOutroTimestamps()).copy(introMs = ms)
    }

    fun setOutro(key: String, ms: Long) {
        val existing = timestamps[key]
        timestamps[key] = (existing ?: IntroOutroTimestamps()).copy(outroMs = ms)
    }
}

@Composable
fun rememberIntroOutroState(): IntroOutroState {
    return remember { IntroOutroState() }
}
