package com.fluxplayer.app.settings.screens.appearance

import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxplayer.app.core.model.AccentPreset
import com.fluxplayer.app.core.model.StartupPage
import com.fluxplayer.app.core.model.ThemeConfig
import com.fluxplayer.app.core.model.NavStyle
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
import com.fluxplayer.app.core.ui.theme.colorFor
import com.fluxplayer.app.core.ui.theme.onAccent
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
                // 强调色是界面上唯一的彩色来源，直接置于分组首位。
                val isDarkNow = when (uiState.preferences.themeConfig) {
                    ThemeConfig.ON -> true
                    ThemeConfig.OFF -> false
                    ThemeConfig.SYSTEM -> isSystemInDarkTheme()
                }
                AccentPresetPicker(
                    selected = uiState.preferences.accentPreset,
                    isDark = isDarkNow,
                    onSelect = { onEvent(AppearancePreferencesEvent.UpdateAccentPreset(it)) },
                )
                HorizontalDivider()
                PreferenceSwitchWithDivider(
                    title = stringResource(id = R.string.dark_theme),
                    description = uiState.preferences.themeConfig.name(),
                    isChecked = uiState.preferences.themeConfig == ThemeConfig.ON,
                    onChecked = { onEvent(AppearancePreferencesEvent.ToggleDarkTheme) },
                    icon = NextIcons.DarkMode,
                    onClick = { onEvent(AppearancePreferencesEvent.ShowDialog(AppearancePreferenceDialog.Theme)) },
                    isFirstItem = false,
                    isLastItem = false
                )
                HorizontalDivider()
                PreferenceSwitch(
                    title = stringResource(R.string.high_contrast_dark_theme),
                    description = stringResource(R.string.high_contrast_dark_theme_desc),
                    icon = NextIcons.Contrast,
                    enabled = isDarkNow,
                    isChecked = uiState.preferences.useHighContrastDarkTheme,
                    onClick = { onEvent(AppearancePreferencesEvent.ToggleUseHighContrastDarkTheme) },
                    isLastItem = false
                )
                HorizontalDivider()
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
                    title = stringResource(R.string.nav_style),
                    description = when (uiState.preferences.navStyle) {
                        NavStyle.DOCK -> stringResource(R.string.nav_style_dock)
                        NavStyle.FULL_BAR -> stringResource(R.string.nav_style_full_bar)
                    },
                    icon = NextIcons.Appearance,
                    enabled = uiState.preferences.useFloatingBottomBar,
                    onClick = { onEvent(AppearancePreferencesEvent.ShowDialog(AppearancePreferenceDialog.NavStyle)) },
                    isLastItem = false
                )
                HorizontalDivider()
                PreferenceItem(
                    title = stringResource(R.string.startup_page),
                    description = uiState.preferences.startupPage.name(),
                    icon = NextIcons.Appearance,
                    enabled = true,
                    onClick = { onEvent(AppearancePreferencesEvent.ShowDialog(AppearancePreferenceDialog.StartupPage)) },
                    isLastItem = false
                )
                HorizontalDivider()
                PreferenceSwitch(
                    title = stringResource(R.string.show_videos_tab),
                    icon = NextIcons.Appearance,
                    isChecked = uiState.preferences.showVideosTab,
                    onClick = { onEvent(AppearancePreferencesEvent.ToggleShowVideosTab) },
                    isLastItem = false
                )
                HorizontalDivider()
                PreferenceSwitch(
                    title = stringResource(R.string.show_browse_tab),
                    icon = NextIcons.Appearance,
                    isChecked = uiState.preferences.showBrowseTab,
                    onClick = { onEvent(AppearancePreferencesEvent.ToggleShowBrowseTab) },
                    isLastItem = false
                )
                HorizontalDivider()
                PreferenceSwitch(
                    title = stringResource(R.string.show_history_tab),
                    icon = NextIcons.Appearance,
                    isChecked = uiState.preferences.showHistoryTab,
                    onClick = { onEvent(AppearancePreferencesEvent.ToggleShowHistoryTab) },
                    isLastItem = false
                )
                HorizontalDivider()
                PreferenceSwitch(
                    title = stringResource(R.string.show_audiobook_tab),
                    icon = NextIcons.Appearance,
                    isChecked = uiState.preferences.showAudiobookTab,
                    onClick = { onEvent(AppearancePreferencesEvent.ToggleShowAudiobookTab) },
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

            // 不透明度设置
            ListSectionTitle(text = stringResource(R.string.opacity))
            Column {
                PreferenceSlider(
                    title = stringResource(R.string.top_bar_opacity),
                    value = uiState.preferences.topBarOpacity.toFloat(),
                    valueRange = 0f..100f,
                    onValueChange = { onEvent(AppearancePreferencesEvent.UpdateTopBarOpacity(it.toInt())) },
                    isFirstItem = true,
                    isLastItem = false
                )
                PreferenceSlider(
                    title = stringResource(R.string.bottom_bar_opacity),
                    value = uiState.preferences.bottomBarOpacity.toFloat(),
                    valueRange = 0f..100f,
                    onValueChange = { onEvent(AppearancePreferencesEvent.UpdateBottomBarOpacity(it.toInt())) },
                    isFirstItem = false,
                    isLastItem = false
                )
                PreferenceSlider(
                    title = stringResource(R.string.container_opacity),
                    value = uiState.preferences.containerOpacity.toFloat(),
                    valueRange = 0f..100f,
                    onValueChange = { onEvent(AppearancePreferencesEvent.UpdateContainerOpacity(it.toInt())) },
                    isFirstItem = false,
                    isLastItem = true
                )
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
                AppearancePreferenceDialog.NavStyle -> {
                    OptionsDialog(
                        text = stringResource(id = R.string.nav_style),
                        onDismissClick = { onEvent(AppearancePreferencesEvent.ShowDialog(null)) },
                    ) {
                        items(NavStyle.entries.toTypedArray()) {
                            RadioTextButton(
                                text = stringResource(
                                    id = when (it) {
                                        NavStyle.DOCK -> R.string.nav_style_dock
                                        NavStyle.FULL_BAR -> R.string.nav_style_full_bar
                                    },
                                ),
                                selected = (it == uiState.preferences.navStyle),
                                onClick = {
                                    onEvent(AppearancePreferencesEvent.UpdateNavStyle(it))
                                    onEvent(AppearancePreferencesEvent.ShowDialog(null))
                                },
                            )
                        }
                    }
                }
                AppearancePreferenceDialog.StartupPage -> {
                    OptionsDialog(
                        text = stringResource(id = R.string.startup_page),
                        onDismissClick = { onEvent(AppearancePreferencesEvent.ShowDialog(null)) },
                    ) {
                        items(StartupPage.entries.toTypedArray()) {
                            RadioTextButton(
                                text = it.name(),
                                selected = (it == uiState.preferences.startupPage),
                                onClick = {
                                    onEvent(AppearancePreferencesEvent.UpdateStartupPage(it))
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

/**
 * 预设主题色选择卡
 */
/**
 * 墨 · 极简风格下的强调色选择：7 色圆点 + 名字，选中高亮描边。
 * 圆点颜色跟随当前明暗（夜间显示更亮的深色变体），点击立即全局生效。
 */
@Composable
private fun AccentPresetPicker(
    selected: AccentPreset,
    isDark: Boolean,
    onSelect: (AccentPreset) -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.accent_color),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            AccentPreset.entries.forEach { preset ->
                val isSelected = preset == selected
                val accentColor = preset.colorFor(isDark)
                val borderColor = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                }
                val borderWidth = if (isSelected) 3.dp else 1.dp

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(accentColor)
                            .border(borderWidth, borderColor, CircleShape)
                            .clickable { onSelect(preset) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(accentColor.onAccent()),
                            )
                        }
                    }
                    Text(
                        text = preset.name(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(top = 4.dp),
                    )
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
