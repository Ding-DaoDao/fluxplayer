package dev.anilbeesetti.nextplayer.feature.videopicker.openlist

import dev.anilbeesetti.nextplayer.core.model.WebDavResource

data class OpenListBrowserUiState(
    val items: List<WebDavResource> = emptyList(),
    val currentPath: String = "/",
    val breadcrumbs: List<Breadcrumb> = listOf(Breadcrumb("根目录", "/")),
    val isLoading: Boolean = false,
    val isConfigured: Boolean = false,
    val hasStorage: Boolean = true,
    val error: String? = null,
    val serverUrl: String = "http://127.0.0.1:5244",
    val playedUriStrings: Set<String> = emptySet(),
    val visitedDirPaths: Set<String> = emptySet(),
    val currentFootprint: String? = null,
    val scrollTargetIndex: Int = -1,
    val scrollTargetParentKey: String? = null,
    val pendingAction: String? = null,
    val moveFileIndex: Int = -1,
    val copyFileIndex: Int = -1,
    val pickerFolders: List<WebDavResource> = emptyList(),
    val pickerIsLoading: Boolean = false,
    val pickerCurrentPath: String = "/",
    val pickerBreadcrumbs: List<Breadcrumb> = listOf(Breadcrumb("根目录", "/")),
)
