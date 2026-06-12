package com.fluxplayer.app.feature.videopicker

import com.fluxplayer.app.core.model.WebDavResource

/**
 * 部分状态更新 —— 对应原版 CommonStateUpdate<TBreadcrumb>
 * 所有字段都可空，非空字段表示需要更新该字段
 */
data class CommonStateUpdate<TBreadcrumb>(
    val items: List<WebDavResource>? = null,
    val currentFileId: String? = null,
    val breadcrumbs: List<TBreadcrumb>? = null,
    val isLoading: Boolean? = null,
    val isLoadingMore: Boolean? = null,
    val hasMore: Boolean? = null,
    val currentPage: Int? = null,
    val isLoggedIn: Boolean? = null,
    val error: String? = null,
    val orderBy: String? = null,
    val orderDirection: String? = null,
    val scrollTargetIndex: Int? = null,
    val scrollTargetOffset: Int? = null,
    val scrollTargetParentKey: String? = null,
    val pendingAction: String? = null,
    val moveFileId: String? = null,
    val copyFileId: String? = null,
    val pickerFolders: List<WebDavResource>? = null,
    val pickerIsLoading: Boolean? = null,
    val playedUriSet: Set<String>? = null,
)
