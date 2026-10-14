package com.fluxplayer.app.core.ui.theme

import android.os.Build
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight

/** 玻璃必须采样真实页面内容，不能用半透明色块代替背景录入。 */
@Composable
fun Modifier.mediaGlass(shape: Shape = RectangleShape, enabled: Boolean = LocalMediaPreferences.current.glassControlsEnabled): Modifier {
    val preferences = LocalMediaPreferences.current
    val backdrop = LocalMediaBackdrop.current
    if (!LocalMediaShell.current || !enabled || backdrop == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this
    // 普通矩形转成零圆角的等效形状；花形等路径只使用背景模糊。
    val backdropShape = if (shape === RectangleShape) RoundedCornerShape(0.dp) else shape
    val supportsLens = backdropShape is CornerBasedShape
    return drawBackdrop(
        backdrop = backdrop,
        shape = { backdropShape },
        effects = {
            vibrancy()
            blur(preferences.glassBlurDp.coerceIn(0f, 40f).dp.toPx())
            colorControls(
                brightness = (0.5f - preferences.glassTranslucency) * 0.24f,
                contrast = 1f + (preferences.glassTranslucency - 0.5f) * 0.5f,
            )
            if (supportsLens) {
                lens(
                    refractionHeight = preferences.glassDistortionDp.coerceIn(0f, 30f).dp.toPx(),
                    refractionAmount = preferences.glassDistortionDp.coerceIn(0f, 30f).dp.toPx(),
                    chromaticAberration = preferences.glassDispersion,
                )
            }
        },
        highlight = { Highlight.Ambient },
    )
}
