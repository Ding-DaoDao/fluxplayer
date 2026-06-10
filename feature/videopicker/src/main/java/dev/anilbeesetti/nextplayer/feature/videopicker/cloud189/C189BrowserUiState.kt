package dev.anilbeesetti.nextplayer.feature.videopicker.cloud189

import dev.anilbeesetti.nextplayer.core.model.WebDavResource

/**
 * 天翼云盘 UI 状态 —— 对应反编译版 C189BrowserUiState
 */
data class C189BrowserUiState(
    val items: List<WebDavResource> = emptyList(),
    val currentFolderId: String = "-11",
    val breadcrumbs: List<C189Breadcrumb> = listOf(C189Breadcrumb("根目录", "-11")),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val currentPage: Int = 1,
    val isLoggedIn: Boolean = false,
    val error: String? = null,
    val orderBy: String = "filename",
    val descending: Boolean = false,
    val currentFootprint: String? = null,
    val scrollTargetIndex: Int = -1,
    val scrollTargetParentKey: String? = null,
    val loginLoading: Boolean = false,
    val pendingAction: String? = null,
    val moveFileId: String? = null,
    val copyFileId: String? = null,
    val pickerFolders: List<WebDavResource> = emptyList(),
    val pickerIsLoading: Boolean = false
)
