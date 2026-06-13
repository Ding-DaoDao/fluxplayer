package com.fluxplayer.app.feature.videopicker.composables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.fluxplayer.app.core.common.Utils
import com.fluxplayer.app.core.ui.components.FluxIcon
import com.fluxplayer.app.core.ui.components.FluxText
import com.fluxplayer.app.core.model.ApplicationPreferences
import com.fluxplayer.app.core.model.Folder
import com.fluxplayer.app.core.model.MediaLayoutMode
import com.fluxplayer.app.core.ui.R
import com.fluxplayer.app.core.ui.components.NextSegmentedListItem
import com.fluxplayer.app.core.ui.designsystem.NextIcons
import com.fluxplayer.app.core.ui.theme.NextPlayerTheme

@Composable
fun FolderItem(
    folder: Folder,
    preferences: ApplicationPreferences,
    modifier: Modifier = Modifier,
    isFirstItem: Boolean = false,
    isLastItem: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    when (preferences.mediaLayoutMode) {
        MediaLayoutMode.LIST -> FolderListItem(
            folder = folder,
            preferences = preferences,
            modifier = modifier,
            isFirstItem = isFirstItem,
            isLastItem = isLastItem,
            selected = selected,
            onClick = onClick,
            onLongClick = onLongClick,
        )
        MediaLayoutMode.GRID -> FolderGridItem(
            folder = folder,
            preferences = preferences,
            modifier = modifier,
            isFirstItem = isFirstItem,
            isLastItem = isLastItem,
            selected = selected,
            onClick = onClick,
            onLongClick = onLongClick,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FolderListItem(
    folder: Folder,
    preferences: ApplicationPreferences,
    modifier: Modifier = Modifier,
    isFirstItem: Boolean = false,
    isLastItem: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    NextSegmentedListItem(
        modifier = modifier,
        selected = selected,
        contentPadding = PaddingValues(8.dp),
        colors = ListItemDefaults.segmentedColors(
            selectedContainerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f),
        ),
        isFirstItem = isFirstItem,
        isLastItem = isLastItem,
        onClick = onClick,
        onLongClick = onLongClick,
        leadingContent = {
            Box(modifier = Modifier.padding(horizontal = 8.dp)) {
                FluxIcon(
                    imageVector = ImageVector.vectorResource(id = R.drawable.folder_thumb),
                    contentDescription = "",
                    tint = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .width(min(90.dp, LocalConfiguration.current.screenWidthDp.dp * 0.3f))
                        .aspectRatio(20 / 17f),
                )

                if (preferences.showDurationField) {
                    InfoChip(
                        text = Utils.formatDurationMillis(folder.mediaDuration),
                        modifier = Modifier
                            .padding(5.dp)
                            .padding(bottom = 3.dp)
                            .align(Alignment.BottomEnd),
                        backgroundColor = Color.Black.copy(alpha = 0.6f),
                        contentColor = Color.White,
                        shape = MaterialTheme.shapes.extraSmall,
                    )
                }
            }
        },
        content = {
            Text(
                text = folder.name,
                maxLines = 2,
                style = MaterialTheme.typography.titleMedium,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (preferences.showPathField) {
                    Text(
                        text = folder.path.substringBeforeLast("/"),
                        maxLines = 2,
                        style = MaterialTheme.typography.bodySmall,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    if (folder.mediaList.isNotEmpty()) {
                        InfoChip(
                            text = "${folder.mediaList.size} " +
                                stringResource(id = R.string.video.takeIf { folder.mediaList.size == 1 } ?: R.string.videos),
                        )
                    }
                    if (folder.folderList.isNotEmpty()) {
                        InfoChip(
                            text = "${folder.folderList.size} " +
                                stringResource(id = R.string.folder.takeIf { folder.folderList.size == 1 } ?: R.string.folders),
                        )
                    }
                    if (preferences.showSizeField) {
                        InfoChip(text = Utils.formatFileSize(folder.mediaSize))
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FolderGridItem(
    folder: Folder,
    preferences: ApplicationPreferences,
    modifier: Modifier = Modifier,
    isFirstItem: Boolean = false,
    isLastItem: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    NextSegmentedListItem(
        modifier = modifier.width(IntrinsicSize.Min),
        selected = selected,
        contentPadding = PaddingValues(8.dp),
        colors = ListItemDefaults.segmentedColors(
            selectedContainerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f),
        ),
        isFirstItem = isFirstItem,
        isLastItem = isLastItem,
        onClick = onClick,
        onLongClick = onLongClick,
        content = {
            Column(
                modifier = modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box {
                    FluxIcon(
                        imageVector = ImageVector.vectorResource(id = R.drawable.folder_thumb),
                        contentDescription = "",
                        tint = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .width(min(90.dp, LocalConfiguration.current.screenWidthDp.dp * 0.3f))
                            .aspectRatio(20 / 17f),
                    )

                    if (preferences.showDurationField) {
                        InfoChip(
                            text = Utils.formatDurationMillis(folder.mediaDuration),
                            modifier = Modifier
                                .padding(5.dp)
                                .padding(bottom = 3.dp)
                                .align(Alignment.BottomEnd),
                            backgroundColor = Color.Black.copy(alpha = 0.6f),
                            contentColor = Color.White,
                            shape = MaterialTheme.shapes.extraSmall,
                        )
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = folder.name,
                        maxLines = 2,
                        style = MaterialTheme.typography.titleMedium,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    val mediaCount = if (folder.mediaList.isNotEmpty()) {
                        "${folder.mediaList.size} " + stringResource(id = R.string.video.takeIf { folder.mediaList.size == 1 } ?: R.string.videos)
                    } else {
                        null
                    }
                    val folderCount = if (folder.folderList.isNotEmpty()) {
                        "${folder.folderList.size} " + stringResource(id = R.string.folder.takeIf { folder.folderList.size == 1 } ?: R.string.folders)
                    } else {
                        null
                    }

                    Text(
                        text = buildString {
                            mediaCount?.let {
                                append(it)
                                folderCount?.let {
                                    append(", ")
                                    append("\u00A0")
                                }
                            }
                            folderCount?.let {
                                append(it)
                            }
                        },
                        maxLines = 2,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        },
    )
}

@PreviewLightDark
@Composable
fun FolderItemPreview() {
    NextPlayerTheme {
        FolderListItem(
            folder = Folder.sample.copy(folderList = listOf(Folder.sample)),
            preferences = ApplicationPreferences(),
        )
    }
}

@PreviewLightDark
@Composable
fun FolderGridViewPreview() {
    NextPlayerTheme {
        FolderGridItem(
            folder = Folder.sample.copy(folderList = listOf(Folder.sample)),
            preferences = ApplicationPreferences(),
        )
    }
}
