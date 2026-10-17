package com.fluxplayer.app.settings

import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.fluxplayer.app.MainActivity
import com.fluxplayer.app.core.data.cache.PlaybackCacheManager
import com.fluxplayer.app.core.data.repository.PreferencesRepository
import com.fluxplayer.app.core.model.ApplicationPreferences
import com.fluxplayer.app.core.model.DecoderPriority
import com.fluxplayer.app.core.model.PlayerPreferences
import com.fluxplayer.app.core.ui.R
import com.fluxplayer.app.core.ui.theme.NextPlayerTheme
import com.fluxplayer.app.settings.screens.appearance.AppearancePreferencesScreen
import com.fluxplayer.app.settings.screens.appearance.AppearancePreferencesViewModel
import com.fluxplayer.app.settings.screens.player.PlayerPreferencesScreen
import com.fluxplayer.app.settings.screens.player.PlayerPreferencesViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** 在共存应用中验证设置页面，沿用真实应用的资源与依赖。 */
class SettingsCleanupUiTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var activity: MainActivity

    @Before
    fun launchSettingsHost() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // 使用系统测试入口启动真实页面，兼容小米系统对测试包跨包启动的限制。
        instrumentation.uiAutomation.executeShellCommand("am start -W -n ${instrumentation.targetContext.packageName}/com.fluxplayer.app.MainActivity").use {
            ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
        instrumentation.runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().single()
        }
    }

    @After
    fun closeSettingsHost() {
        if (::activity.isInitialized) InstrumentationRegistry.getInstrumentation().runOnMainSync { activity.finish() }
    }

    @Test
    fun settingsHomeHasNoSeparateDecoderDestination() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.runOnUiThread { activity.setContent { NextPlayerTheme { SettingsScreen({}, {}) } } }
        compose.onNodeWithText(context.getString(R.string.decoder)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.player_name)).assertIsDisplayed()
        screenshot("settings-home")
    }

    @Test
    fun decoderChoiceInAdvancedPlaybackUpdatesTheSamePreference() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = MemoryPreferences()
        lateinit var model: PlayerPreferencesViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync { model = PlayerPreferencesViewModel(preferences, PlaybackCacheManager(context)) }
        compose.runOnUiThread { activity.setContent { NextPlayerTheme { PlayerPreferencesScreen({}, model) } } }
        compose.onNodeWithText(context.getString(R.string.advanced_playback)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.decoder_priority)).performClick()
        compose.onNodeWithText(context.getString(R.string.prefer_app_decoders)).performClick()
        compose.runOnIdle { assertEquals(DecoderPriority.PREFER_APP, preferences.playerPreferences.value.decoderPriority) }
        screenshot("advanced-playback")
    }

    @Test
    fun glassSlidersAreDisabledWhenAllGlassEffectsAreOff() {
        val preferences = MemoryPreferences(ApplicationPreferences(glassControlsEnabled = false, useLiquidGlass = false))
        lateinit var model: AppearancePreferencesViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync { model = AppearancePreferencesViewModel(preferences) }
        compose.runOnUiThread { activity.setContent { NextPlayerTheme { AppearancePreferencesScreen({}, model) } } }
        val sliders = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
        sliders.assertCountEquals(3)
        repeat(3) { sliders[it].assertIsNotEnabled() }
        compose.onNodeWithText(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.avbox_theme_blur)).performScrollTo()
        screenshot("glass-disabled")
    }

    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File(context.getExternalFilesDir(null), "$name.png")
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private class MemoryPreferences(initial: ApplicationPreferences = ApplicationPreferences()) : PreferencesRepository {
        override val applicationPreferences = MutableStateFlow(initial)
        override val playerPreferences = MutableStateFlow(PlayerPreferences())

        override suspend fun updateApplicationPreferences(transform: suspend (ApplicationPreferences) -> ApplicationPreferences) {
            applicationPreferences.value = transform(applicationPreferences.value)
        }

        override suspend fun updatePlayerPreferences(transform: suspend (PlayerPreferences) -> PlayerPreferences) {
            playerPreferences.value = transform(playerPreferences.value)
        }

        override suspend fun resetPreferences() = Unit
    }
}
