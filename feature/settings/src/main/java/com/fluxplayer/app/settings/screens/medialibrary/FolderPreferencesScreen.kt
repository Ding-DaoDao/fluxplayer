package com.fluxplayer.app.settings.screens.medialibrary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxplayer.app.core.ui.R
import com.fluxplayer.app.core.ui.base.DataState
import com.fluxplayer.app.core.ui.components.FluxSettingsScaffold
import com.fluxplayer.app.core.ui.components.SelectablePreference
import com.fluxplayer.app.core.ui.extensions.plus
import com.fluxplayer.app.core.ui.theme.NextPlayerTheme

@Composable
fun FolderPreferencesScreen(
    onNavigateUp: () -> Unit,
    viewModel: FolderPreferencesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)

    FolderPreferencesContent(
        uiState = uiState,
        onNavigateUp = onNavigateUp,
        onEvent = viewModel::onEvent,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FolderPreferencesContent(
    uiState: FolderPreferencesUiState,
    onNavigateUp: () -> Unit,
    onEvent: (FolderPreferencesUiEvent) -> Unit,
) {
    FluxSettingsScaffold(
        title = stringResource(id = R.string.manage_folders),
        onNavigateUp = onNavigateUp,
    ) { innerPadding ->
        when (uiState.foldersDataState) {
            is DataState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
            }

            is DataState.Success -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = innerPadding + PaddingValues(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                ) {
                    itemsIndexed(uiState.foldersDataState.value) { index, folder ->
                        SelectablePreference(
                            title = folder.name,
                            description = folder.path,
                            selected = folder.path in uiState.preferences.excludeFolders,
                            onClick = { onEvent(FolderPreferencesUiEvent.UpdateExcludeList(folder.path)) },
                            isFirstItem = index == 0,
                            isLastItem = index == uiState.foldersDataState.value.lastIndex,
                        )
                    }
                }
            }

            is DataState.Error -> Unit
        }
    }
}

@PreviewLightDark
@Composable
private fun FolderPreferencesScreenPreview() {
    NextPlayerTheme {
        FolderPreferencesContent(
            uiState = FolderPreferencesUiState(),
            onNavigateUp = {},
            onEvent = {},
        )
    }
}
