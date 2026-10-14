@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.fluxplayer.app.settings.screens.appearance

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxplayer.app.core.model.ApplicationPreferences
import com.fluxplayer.app.core.model.NavStyle
import com.fluxplayer.app.core.model.ThemeColorSource
import com.fluxplayer.app.core.model.ThemeConfig
import com.fluxplayer.app.core.model.ThemePaletteStyle
import com.fluxplayer.app.core.ui.R
import com.fluxplayer.app.core.ui.avbox.CapsuleSegmentedButton
import com.fluxplayer.app.core.ui.avbox.PhoneMockupPreview
import com.fluxplayer.app.core.ui.avbox.RowLeadingIcon
import com.fluxplayer.app.core.ui.avbox.SegmentOption
import com.fluxplayer.app.core.ui.avbox.SettingsCard
import com.fluxplayer.app.core.ui.avbox.SettingsCardPosition
import com.fluxplayer.app.core.ui.avbox.SettingsGroup
import com.fluxplayer.app.core.ui.avbox.SettingsSwitch
import com.fluxplayer.app.core.ui.avbox.SettingsSwitchRow
import com.fluxplayer.app.core.ui.avbox.ThemeColorPickerSheet
import com.fluxplayer.app.core.ui.avbox.filterChipColors
import com.fluxplayer.app.core.ui.components.ClickablePreferenceItem
import com.fluxplayer.app.core.ui.components.FluxSettingsScaffold
import com.fluxplayer.app.core.ui.components.ListSectionTitle
import com.fluxplayer.app.core.ui.components.PreferenceSwitch
import com.fluxplayer.app.core.ui.theme.mediaCustomColorScheme
import com.fluxplayer.app.settings.extensions.name
import kotlin.math.roundToInt

private const val DisabledAlpha = 0.45f

private val PresetSeedCardSpacing = 12.dp

private val PresetSeedMinCardWidth = 80.dp

private const val PresetSeedNarrowColumns = 4

private const val PresetSeedWideColumns = 8

private val PresetSeeds = listOf(R.string.avbox_theme_seed_red to 0xFFD02020.toInt(), R.string.avbox_theme_seed_orange to 0xFFE07A00.toInt(), R.string.avbox_theme_seed_yellow to 0xFFB08000.toInt(), R.string.avbox_theme_seed_green to 0xFF208040.toInt(), R.string.avbox_theme_seed_cyan to 0xFF008080.toInt(), R.string.avbox_theme_seed_blue to 0xFF1B6EF3.toInt(), R.string.avbox_theme_seed_purple to 0xFF6750A4.toInt(), R.string.avbox_theme_seed_pink to 0xFFB04080.toInt())
private val PaletteStyles = listOf(ThemePaletteStyle.TONAL_SPOT to R.string.avbox_theme_style_tonal_spot, ThemePaletteStyle.VIBRANT to R.string.avbox_theme_style_vibrant, ThemePaletteStyle.EXPRESSIVE to R.string.avbox_theme_style_expressive, ThemePaletteStyle.NEUTRAL to R.string.avbox_theme_style_neutral, ThemePaletteStyle.MONOCHROME to R.string.avbox_theme_style_monochrome, ThemePaletteStyle.FIDELITY to R.string.avbox_theme_style_fidelity, ThemePaletteStyle.CONTENT to R.string.avbox_theme_style_content, ThemePaletteStyle.RAINBOW to R.string.avbox_theme_style_rainbow, ThemePaletteStyle.FRUIT_SALAD to R.string.avbox_theme_style_fruit_salad)

@Composable
fun AppearancePreferencesScreen(onNavigateUp: () -> Unit, viewModel: AppearancePreferencesViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AppearanceContent(state, viewModel::onEvent, onNavigateUp)
}

@Composable
private fun AppearanceContent(state: AppearancePreferencesUiState, event: (AppearancePreferencesEvent) -> Unit, onBack: () -> Unit) {
    val p = state.preferences
    val supportsGlass = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val isCustom = p.themeColorSource == ThemeColorSource.CUSTOM
    var colorDialog by remember { mutableStateOf(false) }
    var blur by remember(p.glassBlurDp) { mutableStateOf(p.glassBlurDp) }
    var distortion by remember(p.glassDistortionDp) { mutableStateOf(p.glassDistortionDp) }
    var translucency by remember(p.glassTranslucency) { mutableStateOf(p.glassTranslucency) }
    fun update(change: (ApplicationPreferences) -> ApplicationPreferences) {
        event(AppearancePreferencesEvent.UpdatePreferences(change))
    }
    FluxSettingsScaffold(stringResource(R.string.appearance_name), onBack) { padding ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Spacer(Modifier.height(padding.calculateTopPadding()))
            SettingsCard(SettingsCardPosition.SINGLE) { Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) { PhoneMockupPreview() } }
            SettingsGroup(stringResource(R.string.avbox_theme_color)) {
                ThemeCard(SettingsCardPosition.FIRST) {
                    CustomThemeSwitchRow(isCustom) { checked -> update { it.copy(themeColorSource = if (checked) ThemeColorSource.CUSTOM else ThemeColorSource.SYSTEM) } }
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(stringResource(R.string.avbox_theme_pure_black), p.useHighContrastDarkTheme) { checked -> update { it.copy(useHighContrastDarkTheme = checked) } }
                }
                ThemeCard(SettingsCardPosition.MIDDLE) { ThemeModeRow(p.themeConfig) { mode -> event(AppearancePreferencesEvent.UpdateThemeConfig(mode)) } }
                ThemeCard(SettingsCardPosition.MIDDLE, isCustom) {
                    PresetSeedsRow(p.themeSeedArgb, p.themePaletteStyle, isCustom) { seed -> update { it.copy(themeSeedArgb = seed) } }
                }
                ThemeCard(SettingsCardPosition.MIDDLE, isCustom) { CustomSeedRow(p.themeSeedArgb, isCustom) { colorDialog = true } }
                ThemeCard(SettingsCardPosition.LAST, isCustom) { VariantSelectorRow(p.themePaletteStyle, isCustom) { style -> update { it.copy(themePaletteStyle = style) } } }
            }
            SettingsGroup(stringResource(R.string.avbox_theme_app_effects)) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RowLeadingIcon(R.drawable.ic_theme_liquid_glass, true)
                            Text(stringResource(R.string.avbox_theme_liquid_glass), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { update { it.copy(glassBlurDp = 20f, glassDistortionDp = 30f, glassTranslucency = 0.5f, glassDispersion = true) } }) { Text(stringResource(R.string.avbox_theme_reset)) }
                        }
                        Text(stringResource(R.string.avbox_theme_liquid_glass_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(stringResource(R.string.avbox_theme_nav_bar), p.useLiquidGlass, enabled = supportsGlass) { checked -> update { it.copy(useLiquidGlass = checked, useFloatingBottomBar = if (checked) true else it.useFloatingBottomBar, navStyle = if (checked) NavStyle.DOCK else it.navStyle) } }
                }
                SettingsCard(SettingsCardPosition.MIDDLE) { SettingsSwitchRow(stringResource(R.string.avbox_theme_app_controls), p.glassControlsEnabled, enabled = supportsGlass) { checked -> update { it.copy(glassControlsEnabled = checked) } } }
                SettingsCard(SettingsCardPosition.MIDDLE) { GlassSliderRow(stringResource(R.string.avbox_theme_blur), blur, 0f..40f, { blur = it }, { update { it.copy(glassBlurDp = blur) } }) }
                SettingsCard(SettingsCardPosition.MIDDLE) { GlassSliderRow(stringResource(R.string.avbox_theme_distortion), distortion, 0f..30f, { distortion = it }, { update { it.copy(glassDistortionDp = distortion) } }) }
                SettingsCard(SettingsCardPosition.MIDDLE) { GlassSliderRow(stringResource(R.string.avbox_theme_translucency), translucency, 0f..1f, { translucency = it }, { update { it.copy(glassTranslucency = translucency) } }, "${(translucency * 100f).roundToInt()}%") }
                SettingsCard(SettingsCardPosition.LAST) { SettingsSwitchRow(stringResource(R.string.avbox_theme_dispersion), p.glassDispersion, enabled = supportsGlass && p.glassDistortionDp > 0f) { checked -> update { it.copy(glassDispersion = checked) } } }
            }
            ListSectionTitle(text = "导航与布局")
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                PreferenceSwitch(stringResource(R.string.floating_bottom_bar), isChecked = p.useFloatingBottomBar, onClick = { update { it.copy(useFloatingBottomBar = !it.useFloatingBottomBar) } }, isFirstItem = true)
                ClickablePreferenceItem(stringResource(R.string.nav_style), description = p.navStyle.label, onClick = { event(AppearancePreferencesEvent.ShowDialog(AppearancePreferenceDialog.NavStyle)) })
                PreferenceSwitch("导航切换动画", isChecked = p.navAnimationsEnabled, onClick = { update { it.copy(navAnimationsEnabled = !it.navAnimationsEnabled) } })
                PreferenceSwitch("底栏拖动切换", description = "仅在悬浮胶囊底栏生效", enabled = p.useFloatingBottomBar && p.navStyle == NavStyle.DOCK, isChecked = p.navGesturesEnabled, onClick = { update { it.copy(navGesturesEnabled = !it.navGesturesEnabled) } })
                PreferenceSwitch("隐藏状态栏", description = "应用页面生效，播放器设置保持独立", isChecked = p.hideStatusBar, onClick = { update { it.copy(hideStatusBar = !it.hideStatusBar) } }, isLastItem = true)
            }
            ListSectionTitle(text = stringResource(R.string.startup_page))
            ClickablePreferenceItem(stringResource(R.string.startup_page), description = p.startupPage.name(), onClick = { event(AppearancePreferencesEvent.ShowDialog(AppearancePreferenceDialog.StartupPage)) }, isFirstItem = true, isLastItem = true)
            ListSectionTitle(text = "显示的标签页")
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                PreferenceSwitch(stringResource(R.string.videos), isChecked = p.showVideosTab, onClick = { event(AppearancePreferencesEvent.ToggleShowVideosTab) }, isFirstItem = true)
                PreferenceSwitch(stringResource(R.string.browse), isChecked = p.showBrowseTab, onClick = { event(AppearancePreferencesEvent.ToggleShowBrowseTab) })
                PreferenceSwitch(stringResource(R.string.history), isChecked = p.showHistoryTab, onClick = { event(AppearancePreferencesEvent.ToggleShowHistoryTab) })
                PreferenceSwitch(stringResource(R.string.audiobook), isChecked = p.showAudiobookTab, onClick = { event(AppearancePreferencesEvent.ToggleShowAudiobookTab) }, isLastItem = true)
            }
        }
    }
    if (colorDialog) {
        ThemeColorPickerSheet(
            stringResource(R.string.avbox_theme_custom_color),
            p.themeSeedArgb,
            onConfirm = { seed -> update { it.copy(themeSeedArgb = seed, themeColorSource = ThemeColorSource.CUSTOM) } },
            onDismiss = { colorDialog = false },
        )
    }
    state.showDialog?.let { dialog ->
        com.fluxplayer.app.settings.composables.OptionsDialog(if (dialog == AppearancePreferenceDialog.NavStyle) "底栏样式" else "启动页", { event(AppearancePreferencesEvent.ShowDialog(null)) }) {
            when (dialog) {
                AppearancePreferenceDialog.Theme -> items(ThemeConfig.entries) { mode ->
                    com.fluxplayer.app.core.ui.components.RadioTextButton(mode.name, mode == p.themeConfig, onClick = {
                        event(AppearancePreferencesEvent.UpdateThemeConfig(mode))
                        event(AppearancePreferencesEvent.ShowDialog(null))
                    })
                }
                AppearancePreferenceDialog.NavStyle -> items(NavStyle.entries) { style ->
                    com.fluxplayer.app.core.ui.components.RadioTextButton(style.label, style == p.navStyle, onClick = {
                        event(AppearancePreferencesEvent.UpdateNavStyle(style))
                        event(AppearancePreferencesEvent.ShowDialog(null))
                    })
                }
                AppearancePreferenceDialog.StartupPage -> items(com.fluxplayer.app.core.model.StartupPage.entries) { page ->
                    com.fluxplayer.app.core.ui.components.RadioTextButton(page.name, page == p.startupPage, onClick = {
                        event(AppearancePreferencesEvent.UpdateStartupPage(page))
                        event(AppearancePreferencesEvent.ShowDialog(null))
                    })
                }
            }
        }
    }
}

@Composable
private fun ThemeCard(
    position: SettingsCardPosition,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    SettingsCard(
        position = position,
        modifier = Modifier.then(if (enabled) Modifier else Modifier.alpha(DisabledAlpha)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            content()
        }
    }
}

@Composable
private fun CustomThemeSwitchRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowLeadingIcon(R.drawable.ic_theme_custom, enabled = true)
        Text(
            text = stringResource(R.string.avbox_theme_custom_theme),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(16.dp))
        SettingsSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun GlassSliderRow(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueText: String = value.roundToInt().toString(),
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(16.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = 0,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledThumbColor = MaterialTheme.colorScheme.outline,
                disabledActiveTrackColor = MaterialTheme.colorScheme.outline,
                disabledInactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        )
    }
}

@Composable
private fun ThemeModeRow(currentMode: ThemeConfig, onModeSelected: (ThemeConfig) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.avbox_theme_mode),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        CapsuleSegmentedButton(
            options = listOf(
                SegmentOption(
                    label = stringResource(R.string.avbox_theme_mode_auto),
                    value = ThemeConfig.SYSTEM,
                    iconPainter = painterResource(R.drawable.ic_brightness_auto),
                ),
                SegmentOption(
                    label = stringResource(R.string.avbox_theme_mode_light),
                    value = ThemeConfig.OFF,
                    iconPainter = painterResource(R.drawable.ic_light_mode),
                ),
                SegmentOption(
                    label = stringResource(R.string.avbox_theme_mode_dark),
                    value = ThemeConfig.ON,
                    iconPainter = painterResource(R.drawable.ic_dark_mode),
                ),
            ),
            selectedValue = currentMode,
            onOptionSelected = onModeSelected,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CustomSeedRow(seedArgb: Int, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.avbox_theme_custom_color),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(16.dp))
        val seedShape = MaterialTheme.shapes.small
        Surface(
            modifier = Modifier
                .size(40.dp)
                .clip(seedShape)
                .clickable(enabled = enabled, onClick = onClick),
            shape = seedShape,
            color = Color(seedArgb),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {}
    }
}

@Composable
private fun VariantSelectorRow(
    currentStyle: ThemePaletteStyle,
    enabled: Boolean,
    onStyleSelected: (ThemePaletteStyle) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.avbox_theme_palette_style),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PaletteStyles.forEach { (style, labelRes) ->
                FilterChip(
                    selected = currentStyle == style,
                    onClick = { onStyleSelected(style) },
                    enabled = enabled,
                    label = { Text(stringResource(labelRes)) },
                    colors = MaterialTheme.colorScheme.filterChipColors(),
                )
            }
        }
    }
}

@Composable
private fun PresetSeedsRow(
    currentSeed: Int,
    style: ThemePaletteStyle,
    enabled: Boolean,
    onSeedSelected: (Int) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val wideColumnsMinWidth = PresetSeedMinCardWidth * PresetSeedWideColumns +
            PresetSeedCardSpacing * (PresetSeedWideColumns - 1)
        val columns =
            if (maxWidth >= wideColumnsMinWidth) {
                PresetSeedWideColumns
            } else {
                PresetSeedNarrowColumns
            }
        Column {
            PresetSeeds.chunked(columns).forEachIndexed { rowIndex, rowItems ->
                if (rowIndex > 0) Spacer(Modifier.height(PresetSeedCardSpacing))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(PresetSeedCardSpacing),
                ) {
                    rowItems.forEach { (nameRes, argb) ->
                        key(argb, style) {
                            PresetSeedCard(
                                nameRes = nameRes,
                                seedArgb = argb,
                                selected = currentSeed == argb,
                                style = style,
                                enabled = enabled,
                                onClick = { onSeedSelected(argb) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    repeat(columns - rowItems.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetSeedCard(
    nameRes: Int,
    seedArgb: Int,
    selected: Boolean,
    style: ThemePaletteStyle,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val previewScheme by produceState(
        initialValue = MaterialTheme.colorScheme,
        key1 = seedArgb,
        key2 = style,
    ) {
        value = mediaCustomColorScheme(seedArgb, style)
    }
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val cardShape = RoundedCornerShape(16.dp)
    Surface(
        modifier = modifier
            .aspectRatio(1f)
            .clip(cardShape)
            .clickable(enabled = enabled, onClick = onClick),
        shape = cardShape,
        color = previewScheme.surfaceContainer,
        border = BorderStroke(2.dp, borderColor),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val unit = maxWidth
            val barHeight = unit * 0.14f
            val gap = unit * 0.05f
            val blockCorner = unit * 0.04f
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(unit * 0.08f),
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth(0.6f)
                        .height(barHeight)
                        .background(previewScheme.primary, RoundedCornerShape(barHeight / 2)),
                )
                if (selected) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(unit * 0.25f),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                    ) {
                        Icon(
                            imageVector = com.fluxplayer.app.core.ui.designsystem.NextIcons.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(unit * 0.04f),
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(barHeight)
                                .background(previewScheme.secondary, RoundedCornerShape(blockCorner)),
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(barHeight)
                                .background(previewScheme.tertiary, RoundedCornerShape(blockCorner)),
                        )
                    }
                    Spacer(Modifier.height(gap))
                    Text(
                        text = stringResource(nameRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = previewScheme.onSurface,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
