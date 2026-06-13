package com.fluxplayer.app.settings.screens.appearance

import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxplayer.app.core.model.ComposeEngine
import com.fluxplayer.app.core.model.ThemeConfig
import com.fluxplayer.app.core.ui.R
import com.fluxplayer.app.core.ui.components.ListSectionTitle
import com.fluxplayer.app.core.ui.components.PreferenceItem
import com.fluxplayer.app.core.ui.components.FluxSettingsScaffold
import com.fluxplayer.app.core.ui.components.PreferenceSlider
import com.fluxplayer.app.core.ui.components.PreferenceSwitch
import com.fluxplayer.app.core.ui.components.PreferenceSwitchWithDivider
import com.fluxplayer.app.core.ui.components.RadioTextButton
import com.fluxplayer.app.core.ui.designsystem.NextIcons
import com.fluxplayer.app.core.ui.theme.NextPlayerTheme
import com.fluxplayer.app.core.ui.theme.supportsDynamicTheming
import com.fluxplayer.app.settings.composables.OptionsDialog
import com.fluxplayer.app.settings.extensions.name

@Composable
fun AppearancePreferencesScreen(
    onNavigateUp: () -> Unit,
    viewModel: AppearancePreferencesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    AppearancePreferencesContent(
        uiState = uiState,
        onEvent = viewModel::onEvent,
        onNavigateUp = onNavigateUp,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppearancePreferencesContent(
    uiState: AppearancePreferencesUiState,
    onEvent: (AppearancePreferencesEvent) -> Unit,
    onNavigateUp: () -> Unit = {},
) {
    FluxSettingsScaffold(
        title = stringResource(id = R.string.appearance_name),
        onNavigateUp = onNavigateUp,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(state = rememberScrollState())
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            ListSectionTitle(text = stringResource(id = R.string.appearance_name))
            Column {
                PreferenceSwitchWithDivider(
                    title = stringResource(id = R.string.dark_theme),
                    description = uiState.preferences.themeConfig.name(),
                    isChecked = uiState.preferences.themeConfig == ThemeConfig.ON,
                    onChecked = { onEvent(AppearancePreferencesEvent.ToggleDarkTheme) },
                    icon = NextIcons.DarkMode,
                    onClick = { onEvent(AppearancePreferencesEvent.ShowDialog(AppearancePreferenceDialog.Theme)) },
                    isFirstItem = true
                )
                HorizontalDivider()
                PreferenceSwitch(
                    title = stringResource(R.string.high_contrast_dark_theme),
                    description = stringResource(R.string.high_contrast_dark_theme_desc),
                    icon = NextIcons.Contrast,
                    isChecked = uiState.preferences.useHighContrastDarkTheme,
                    onClick = { onEvent(AppearancePreferencesEvent.ToggleUseHighContrastDarkTheme) },
                    isLastItem = !supportsDynamicTheming()
                )
                if (supportsDynamicTheming()) {
                    HorizontalDivider()
                    PreferenceSwitch(
                        title = stringResource(id = R.string.dynamic_theme),
                        description = stringResource(id = R.string.dynamic_theme_description),
                        icon = NextIcons.Appearance,
                        isChecked = uiState.preferences.useDynamicColors,
                        onClick = { onEvent(AppearancePreferencesEvent.ToggleUseDynamicColors) },
                        isLastItem = false
                    )
                }
                HorizontalDivider()
                val isMiuix = uiState.preferences.composeEngine == ComposeEngine.MIUIX
                PreferenceSwitch(
                    title = stringResource(R.string.floating_bottom_bar),
                    description = stringResource(R.string.floating_bottom_bar_description),
                    icon = NextIcons.Appearance,
                    isChecked = uiState.preferences.useFloatingBottomBar,
                    onClick = { onEvent(AppearancePreferencesEvent.ToggleUseFloatingBottomBar) },
                    isLastItem = false
                )
                HorizontalDivider()
                PreferenceSwitch(
                    title = stringResource(R.string.liquid_glass),
                    description = stringResource(R.string.liquid_glass_description),
                    icon = NextIcons.Appearance,
                    isChecked = uiState.preferences.useLiquidGlass,
                    onClick = { onEvent(AppearancePreferencesEvent.ToggleUseLiquidGlass) },
                    isLastItem = false,
                    enabled = uiState.preferences.useFloatingBottomBar,
                )
                HorizontalDivider()
                PreferenceItem(
                    title = stringResource(R.string.compose_engine),
                    description = uiState.preferences.composeEngine.name(),
                    icon = NextIcons.Appearance,
                    enabled = true,
                    onClick = { onEvent(AppearancePreferencesEvent.ShowDialog(AppearancePreferenceDialog.ComposeEngine)) },
                    isLastItem = true
                )
            }

            // 模糊效果设置
            ListSectionTitle(text = stringResource(R.string.blur_effect))
            Column(
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                PreferenceSwitch(
                    title = stringResource(R.string.blur_effect),
                    description = stringResource(R.string.blur_effect_description),
                    icon = NextIcons.Appearance,
                    isChecked = uiState.preferences.enableBlur,
                    onClick = { onEvent(AppearancePreferencesEvent.ToggleEnableBlur) },
                    isFirstItem = true,
                    isLastItem = !uiState.preferences.enableBlur
                )
                if (uiState.preferences.enableBlur) {
                    PreferenceSwitch(
                        title = stringResource(R.string.progressive_blur),
                        description = stringResource(R.string.progressive_blur_description),
                        icon = NextIcons.Appearance,
                        isChecked = uiState.preferences.enableProgressiveBlur,
                        onClick = { onEvent(AppearancePreferencesEvent.ToggleEnableProgressiveBlur) },
                        isLastItem = false
                    )
                    PreferenceSlider(
                        title = stringResource(R.string.blur_radius) + " · " + stringResource(R.string.top_bar),
                        value = uiState.preferences.topBarBlurRadius.toFloat(),
                        valueRange = 0f..50f,
                        onValueChange = { onEvent(AppearancePreferencesEvent.UpdateTopBarBlurRadius(it.toInt())) },
                        isFirstItem = false,
                        isLastItem = false
                    )
                    PreferenceSlider(
                        title = stringResource(R.string.blur_alpha) + " · " + stringResource(R.string.top_bar),
                        value = uiState.preferences.topBarBlurAlpha.toFloat(),
                        valueRange = 0f..100f,
                        onValueChange = { onEvent(AppearancePreferencesEvent.UpdateTopBarBlurAlpha(it.toInt())) },
                        isFirstItem = false,
                        isLastItem = false
                    )
                    PreferenceSlider(
                        title = stringResource(R.string.blur_radius) + " · " + stringResource(R.string.bottom_bar),
                        value = uiState.preferences.bottomBarBlurRadius.toFloat(),
                        valueRange = 0f..50f,
                        onValueChange = { onEvent(AppearancePreferencesEvent.UpdateBottomBarBlurRadius(it.toInt())) },
                        isFirstItem = false,
                        isLastItem = false
                    )
                    PreferenceSlider(
                        title = stringResource(R.string.blur_alpha) + " · " + stringResource(R.string.bottom_bar),
                        value = uiState.preferences.bottomBarBlurAlpha.toFloat(),
                        valueRange = 0f..100f,
                        onValueChange = { onEvent(AppearancePreferencesEvent.UpdateBottomBarBlurAlpha(it.toInt())) },
                        isFirstItem = false,
                        isLastItem = true
                    )
                }
            }
        }

        uiState.showDialog?.let { showDialog ->
            when (showDialog) {
                AppearancePreferenceDialog.Theme -> {
                    OptionsDialog(
                        text = stringResource(id = R.string.dark_theme),
                        onDismissClick = { onEvent(AppearancePreferencesEvent.ShowDialog(null)) },
                    ) {
                        items(ThemeConfig.entries.toTypedArray()) {
                            RadioTextButton(
                                text = it.name(),
                                selected = (it == uiState.preferences.themeConfig),
                                onClick = {
                                    onEvent(AppearancePreferencesEvent.UpdateThemeConfig(it))
                                    onEvent(AppearancePreferencesEvent.ShowDialog(null))
                                },
                            )
                        }
                    }
                }
                AppearancePreferenceDialog.ComposeEngine -> {
                    OptionsDialog(
                        text = stringResource(id = R.string.compose_engine),
                        onDismissClick = { onEvent(AppearancePreferencesEvent.ShowDialog(null)) },
                    ) {
                        items(ComposeEngine.entries.toTypedArray()) {
                            RadioTextButton(
                                text = it.name(),
                                selected = (it == uiState.preferences.composeEngine),
                                onClick = {
                                    onEvent(AppearancePreferencesEvent.UpdateComposeEngine(it))
                                    onEvent(AppearancePreferencesEvent.ShowDialog(null))
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun AppearancePreferencesScreenPreview() {
    NextPlayerTheme {
        AppearancePreferencesContent(
            uiState = AppearancePreferencesUiState(),
            onEvent = {},
        )
    }
}
