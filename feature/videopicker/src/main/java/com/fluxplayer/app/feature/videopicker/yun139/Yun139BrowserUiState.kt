package com.fluxplayer.app.feature.videopicker.yun139

import com.fluxplayer.app.core.model.WebDavResource

/**
 * 移动云盘 UI 状态
 */
data class Yun139BrowserUiState(
    val items: List<WebDavResource> = emptyList(),
    val currentFolderId: String = "/",
    val breadcrumbs: List<Yun139Breadcrumb> = listOf(Yun139Breadcrumb("根目录", "/")),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val nextPageCursor: String? = null,
    val isLoggedIn: Boolean = false,
    val error: String? = null,
    val orderBy: String = "name",
    val orderDirection: String = "ASC",
    val playedUriStrings: Set<String> = emptySet(),
    val scrollTargetIndex: Int = -1,
    val scrollTargetParentKey: String? = null,
    val pendingAction: String? = null,
    val moveFileId: String? = null,
    val pickerFolders: List<WebDavResource> = emptyList(),
    val pickerIsLoading: Boolean = false
)
