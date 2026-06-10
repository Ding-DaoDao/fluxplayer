package dev.anilbeesetti.nextplayer.feature.videopicker.quark

import dev.anilbeesetti.nextplayer.core.model.WebDavResource

/**
 * 夸克网盘 UI 状态 —— 对应反编译版 QuarkBrowserUiState
 */
data class QuarkBrowserUiState(
    val items: List<WebDavResource> = emptyList(),
    val currentFileId: String = "0",
    val breadcrumbs: List<QuarkBreadcrumb> = listOf(QuarkBreadcrumb("根目录", "0")),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val currentPage: Int = 1,
    val isLoggedIn: Boolean = false,
    val error: String? = null,
    val currentFootprint: String? = null,
    val scrollTargetIndex: Int = -1,
    val scrollTargetParentKey: String? = null,
    val driveType: String = "quark",
    val orderBy: String = "file_name:asc",
    val pendingAction: String? = null,
    val moveFileId: String? = null,
    val pickerFolders: List<WebDavResource> = emptyList(),
    val pickerIsLoading: Boolean = false
)
