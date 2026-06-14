package com.fluxplayer.app.settings.screens.appearance

import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
                if (!uiState.preferences.useDynamicColors) {
                    HorizontalDivider()
                    ThemeColorPicker(
                        selectedColor = uiState.preferences.customSeedColor,
                        onColorSelected = { color ->
                            onEvent(AppearancePreferencesEvent.UpdateCustomSeedColor(color))
                        },
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

/**
 * 预设主题色选择卡
 */
private data class PresetColor(val color: Int, val label: String)

private val presetColors = listOf(
    PresetColor(0xFF3482FF.toInt(), "Blue"),
    PresetColor(0xFFE53935.toInt(), "Red"),
    PresetColor(0xFF43A047.toInt(), "Green"),
    PresetColor(0xFF8E24AA.toInt(), "Purple"),
    PresetColor(0xFFFB8C00.toInt(), "Orange"),
    PresetColor(0xFFD81B60.toInt(), "Pink"),
    PresetColor(0xFF00897B.toInt(), "Teal"),
    PresetColor(0xFF3949AB.toInt(), "Indigo"),
)

@Composable
private fun ThemeColorPicker(
    selectedColor: Int,
    onColorSelected: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        androidx.compose.material3.Text(
            text = stringResource(R.string.theme_color),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(presetColors) { preset ->
                val isSelected = selectedColor == preset.color
                val borderColor = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant
                val borderWidth = if (isSelected) 3.dp else 1.dp

                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(preset.color))
                        .border(borderWidth, borderColor, CircleShape)
                        .clickable { onColorSelected(preset.color) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(Color.White),
                        )
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
