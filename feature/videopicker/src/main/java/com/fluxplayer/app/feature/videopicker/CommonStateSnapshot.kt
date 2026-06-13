package com.fluxplayer.app.feature.videopicker

import com.fluxplayer.app.core.model.WebDavResource

/**
 * 不可变状态快照 —— 对应原版 CommonStateSnapshot<TBreadcrumb>
 * 每个云盘 ViewModel 持有此快照，UI 层通过 readState() 获取当前状态
 */
data class CommonStateSnapshot<TBreadcrumb>(
    val items: List<WebDavResource> = emptyList(),
    val currentFileId: String = "",
    val breadcrumbs: List<TBreadcrumb> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val currentPage: Int = 1,
    val isLoggedIn: Boolean = false,
    val error: String? = null,
    val orderBy: String = "",
    val orderDirection: String = "",
    val scrollTargetIndex: Int = -1,
    val scrollTargetOffset: Int = 0,
    val scrollTargetParentKey: String? = null,
    val pendingAction: String? = null,
    val moveFileId: String? = null,
    val pickerFolders: List<WebDavResource> = emptyList(),
    val pickerIsLoading: Boolean = false,
    val playedUriSet: Set<String> = emptySet(),
)
