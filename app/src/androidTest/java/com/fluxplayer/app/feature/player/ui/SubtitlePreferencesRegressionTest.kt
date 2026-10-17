package com.fluxplayer.app.feature.player.ui

import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import androidx.media3.common.text.Cue
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxplayer.app.core.model.Font
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/** 在共存应用中验证字幕视图，保留功能模块内部方法的可见性。 */
class SubtitlePreferencesRegressionTest {
    @Test
    fun existingSubtitleViewAppliesStyleFontAndEmbeddedStyleChanges() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = SubtitleView(instrumentation.targetContext)
            val initial = SubtitleConfiguration(false, false, Font.DEFAULT, 20, true, true)
            apply(view, initial, CaptionStyleCompat.DEFAULT, 1f, false)
            val updated = initial.copy(showBackground = true, textSize = 32, textBold = false, applyEmbeddedStyles = false)
            val style = CaptionStyleCompat(Color.YELLOW, Color.BLACK, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_NONE, Color.BLACK, Typeface.MONOSPACE)
            apply(view, updated, style, 1f, false)
            assertSame(style, field(view, "style"))
            assertFalse(field(view, "applyEmbeddedStyles") as Boolean)
            assertEquals(Cue.TEXT_SIZE_TYPE_ABSOLUTE, field(view, "defaultTextSizeType"))
            assertEquals(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 32f, view.resources.displayMetrics), field(view, "defaultTextSize") as Float, 0.001f)
        }
    }

    @Test
    fun systemFontScaleOverridesDisabledCustomSizeAndUpdatesInPictureInPicture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = SubtitleView(instrumentation.targetContext)
            val configuration = SubtitleConfiguration(true, false, Font.DEFAULT, 48, true, true)
            apply(view, configuration, CaptionStyleCompat.DEFAULT, 1.5f, false)
            assertEquals(Cue.TEXT_SIZE_TYPE_FRACTIONAL, field(view, "defaultTextSizeType"))
            assertEquals(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.5f, field(view, "defaultTextSize") as Float, 0.0001f)
            apply(view, configuration, CaptionStyleCompat.DEFAULT, 2f, true)
            assertEquals(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 2f, field(view, "defaultTextSize") as Float, 0.0001f)
        }
    }

    private fun field(view: SubtitleView, name: String): Any = SubtitleView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(view)!!

    private fun apply(view: SubtitleView, configuration: SubtitleConfiguration, style: CaptionStyleCompat, scale: Float, pip: Boolean) {
        // 通过内部实现检查真实视图，不为测试扩大方法的可见性。
        Class.forName("com.fluxplayer.app.feature.player.ui.SubtitleViewKt").declaredMethods
            .single { it.name == "applySubtitlePreferences" }
            .invoke(null, view, configuration, style, scale, pip)
    }
}
