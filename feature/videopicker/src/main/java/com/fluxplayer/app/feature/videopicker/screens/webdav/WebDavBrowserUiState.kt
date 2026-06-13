package com.fluxplayer.app.feature.videopicker.screens.webdav

import com.fluxplayer.app.core.model.WebDavResource
import com.fluxplayer.app.core.model.WebDavServer

data class WebDavBrowserUiState(
    val items: List<WebDavResource> = emptyList(),
    val currentPath: String = "/",
    val breadcrumbs: List<Breadcrumb> = listOf(Breadcrumb("根目录", "/")),
    val isLoading: Boolean = false,
    val error: String? = null,
    val servers: List<WebDavServer> = emptyList(),
    val activeServers: List<WebDavServer> = emptyList(),
    val selectedServer: WebDavServer? = null,
    val isConfigured: Boolean = false,
    val playedUriStrings: Set<String> = emptySet(),
    val visitedDirPaths: Set<String> = emptySet(),
    val scrollTargetIndex: Int = -1,
    val scrollTargetParentKey: String? = null,
    val pendingAction: String? = null,
    val moveFileIndex: Int = -1,
    val pickerFolders: List<WebDavResource> = emptyList(),
    val pickerIsLoading: Boolean = false,
    val pickerCurrentPath: String = "/",
    val pickerBreadcrumbs: List<Breadcrumb> = listOf(Breadcrumb("根目录", "/")),
)
