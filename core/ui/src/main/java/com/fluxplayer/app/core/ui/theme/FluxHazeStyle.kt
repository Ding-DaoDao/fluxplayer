package com.fluxplayer.app.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fluxplayer.app.core.model.ComposeEngine
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Engine-aware haze style factory with configurable blur parameters.
 */
object FluxHazeStyle {

    @Composable
    fun topBarStyle(
        blurRadius: Int = 24,
        blurAlpha: Int = 73,
    ): HazeStyle {
        val isMiuix = FluxTheme.engine == ComposeEngine.MIUIX
        val tint = if (isMiuix) {
            MiuixTheme.colorScheme.surface
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        }
        return HazeStyle(
            backgroundColor = tint,
            tint = HazeTint(tint.copy(alpha = blurAlpha / 100f)),
            blurRadius = blurRadius.dp,
        )
    }

    @Composable
    fun bottomBarStyle(
        blurRadius: Int = 25,
        blurAlpha: Int = 73,
    ): HazeStyle {
        val isMiuix = FluxTheme.engine == ComposeEngine.MIUIX
        val tint = if (isMiuix) {
            MiuixTheme.colorScheme.surface
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        }
        return HazeStyle(
            backgroundColor = tint,
            tint = HazeTint(tint.copy(alpha = blurAlpha / 100f)),
            blurRadius = blurRadius.dp,
        )
    }

    @Composable
    fun topBarContainerColor(
        blurAlpha: Int = 73,
        enableBlur: Boolean = true,
    ): Color {
        val isMiuix = FluxTheme.engine == ComposeEngine.MIUIX
        val base = if (isMiuix) {
            MiuixTheme.colorScheme.surface
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        }
        return if (enableBlur) base.copy(alpha = blurAlpha / 100f) else base
    }

    @Composable
    fun bottomBarContainerColor(
        enableBlur: Boolean = true,
    ): Color {
        val isMiuix = FluxTheme.engine == ComposeEngine.MIUIX
        val base = if (isMiuix) {
            MiuixTheme.colorScheme.surface
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        }
        return if (enableBlur) Color.Transparent else base
    }
}
