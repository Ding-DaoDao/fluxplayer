package com.fluxplayer.app.feature.player.ui

import android.graphics.Typeface
import android.util.TypedValue
import android.view.accessibility.CaptioningManager
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat.getSystemService
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.fluxplayer.app.core.model.Font
import com.fluxplayer.app.feature.player.extensions.toTypeface
import com.fluxplayer.app.feature.player.state.rememberCuesState

@OptIn(UnstableApi::class)
@Composable
fun SubtitleView(
    modifier: Modifier = Modifier,
    player: Player,
    isInPictureInPictureMode: Boolean,
    configuration: SubtitleConfiguration,
) {
    val cuesState = rememberCuesState(player)
    val context = LocalContext.current
    val captioningManager = remember(context) { getSystemService(context, CaptioningManager::class.java) }
    var systemStyle by remember(captioningManager) {
        mutableStateOf(captioningManager?.userStyle?.let(CaptionStyleCompat::createFromCaptionStyle) ?: CaptionStyleCompat.DEFAULT)
    }
    var systemFontScale by remember(captioningManager) { mutableFloatStateOf(captioningManager?.fontScale ?: 1f) }
    DisposableEffect(captioningManager) {
        val listener = object : CaptioningManager.CaptioningChangeListener() {
            override fun onUserStyleChanged(userStyle: CaptioningManager.CaptionStyle) {
                systemStyle = CaptionStyleCompat.createFromCaptionStyle(userStyle)
            }

            override fun onFontScaleChanged(fontScale: Float) {
                systemFontScale = fontScale
            }
        }
        captioningManager?.addCaptioningChangeListener(listener)
        onDispose { captioningManager?.removeCaptioningChangeListener(listener) }
    }
    val style = remember(configuration, systemStyle) {
        if (configuration.useSystemCaptionStyle) {
            systemStyle
        } else {
            CaptionStyleCompat(
                android.graphics.Color.WHITE,
                android.graphics.Color.BLACK.takeIf { configuration.showBackground } ?: android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                android.graphics.Color.BLACK,
                Typeface.create(configuration.font.toTypeface(), if (configuration.textBold) Typeface.BOLD else Typeface.NORMAL),
            )
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { SubtitleView(it) },
        update = { subtitleView ->
            subtitleView.applySubtitlePreferences(configuration, style, systemFontScale, isInPictureInPictureMode)
            subtitleView.setCues(cuesState.cues)
        },
    )
}

/** 更新现有字幕视图，系统字号不再被已禁用的自定义字号覆盖。 */
@OptIn(UnstableApi::class)
internal fun SubtitleView.applySubtitlePreferences(
    configuration: SubtitleConfiguration,
    style: CaptionStyleCompat,
    systemFontScale: Float,
    isInPictureInPictureMode: Boolean,
) {
    setStyle(style)
    setApplyEmbeddedStyles(configuration.applyEmbeddedStyles)
    when {
        configuration.useSystemCaptionStyle -> setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * systemFontScale)
        isInPictureInPictureMode -> setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION)
        else -> setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, configuration.textSize.toFloat())
    }
}

@Stable
data class SubtitleConfiguration(
    val useSystemCaptionStyle: Boolean,
    val showBackground: Boolean,
    val font: Font,
    val textSize: Int,
    val textBold: Boolean,
    val applyEmbeddedStyles: Boolean,
)
