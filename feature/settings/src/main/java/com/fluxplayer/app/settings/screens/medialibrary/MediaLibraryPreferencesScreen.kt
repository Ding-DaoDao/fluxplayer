package com.fluxplayer.app.settings.screens.medialibrary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxplayer.app.core.model.ThumbnailGenerationStrategy
import com.fluxplayer.app.core.ui.R
import com.fluxplayer.app.core.ui.components.ClickablePreferenceItem
import com.fluxplayer.app.core.ui.components.ListSectionTitle
import com.fluxplayer.app.core.ui.components.NextTopAppBar
import com.fluxplayer.app.core.ui.components.PreferenceSwitch
import com.fluxplayer.app.core.ui.designsystem.NextIcons
import com.fluxplayer.app.core.ui.theme.NextPlayerTheme

@Composable
fun MediaLibraryPreferencesScreen(
    onNavigateUp: () -> Unit,
    onFolderSettingClick: () -> Unit = {},
    onThumbnailSettingClick: () -> Unit = {},
    viewModel: MediaLibraryPreferencesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    MediaLibraryPreferencesContent(
        uiState = uiState,
        onNavigateUp = onNavigateUp,
        onFolderSettingClick = onFolderSettingClick,
        onThumbnailSettingClick = onThumbnailSettingClick,
        onEvent = viewModel::onEvent,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MediaLibraryPreferencesContent(
    uiState: MediaLibraryPreferencesUiState,
    onNavigateUp: () -> Unit,
    onFolderSettingClick: () -> Unit,
    onThumbnailSettingClick: () -> Unit,
    onEvent: (MediaLibraryPreferencesUiEvent) -> Unit,
) {
    val preferences = uiState.preferences

    Scaffold(
        topBar = {
            NextTopAppBar(
                title = stringResource(id = R.string.media_library),
                navigationIcon = {
                    FilledTonalIconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = NextIcons.ArrowBack,
                            contentDescription = stringResource(id = R.string.navigate_up),
                        )
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(state = rememberScrollState())
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            ListSectionTitle(text = stringResource(id = R.string.media_library))
            Column(
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                PreferenceSwitch(
                    title = stringResource(id = R.string.mark_last_played_media),
                    description = stringResource(
                        id = R.string.mark_last_played_media_desc,
                    ),
                    icon = NextIcons.Check,
                    isChecked = preferences.markLastPlayedMedia,
                    onClick = { onEvent(MediaLibraryPreferencesUiEvent.ToggleMarkLastPlayedMedia) },
                    isFirstItem = true,
                    isLastItem = true,
                )
            }

            ListSectionTitle(text = stringResource(id = R.string.scan))
            Column(
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                ClickablePreferenceItem(
                    title = stringResource(id = R.string.manage_folders),
                    description = stringResource(id = R.string.manage_folders_desc),
                    icon = NextIcons.FolderOff,
                    onClick = onFolderSettingClick,
                    isFirstItem = true,
                    isLastItem = true,
                )
            }

            ListSectionTitle(text = stringResource(id = R.string.thumbnail))
            Column(
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                ClickablePreferenceItem(
                    title = stringResource(id = R.string.thumbnail_generation),
                    description = when (preferences.thumbnailGenerationStrategy) {
                        ThumbnailGenerationStrategy.FIRST_FRAME -> stringResource(id = R.string.first_frame)
                        ThumbnailGenerationStrategy.FRAME_AT_PERCENTAGE -> stringResource(R.string.frame_at_position)
                        ThumbnailGenerationStrategy.HYBRID -> stringResource(id = R.string.hybrid)
                    },
                    icon = NextIcons.Image,
                    onClick = onThumbnailSettingClick,
                    isFirstItem = true,
                    isLastItem = true,
                )
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun MediaLibraryPreferencesScreenPreview() {
    NextPlayerTheme {
        MediaLibraryPreferencesContent(
            uiState = MediaLibraryPreferencesUiState(),
            onNavigateUp = {},
            onFolderSettingClick = {},
            onThumbnailSettingClick = {},
            onEvent = {},
        )
    }
}
