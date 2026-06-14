package com.fluxplayer.app.feature.videopicker.pan123

import com.fluxplayer.app.core.data.pan123.Pan123UserInfo
import com.fluxplayer.app.core.model.WebDavResource

/**
 * 123云盘 UI 状态 —— 对应反编译版 Pan123BrowserUiState
 */
data class Pan123BrowserUiState(
    val items: List<WebDavResource> = emptyList(),
    val currentFileId: String = "0",
    val breadcrumbs: List<Pan123Breadcrumb> = listOf(Pan123Breadcrumb("根目录", "0")),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val currentPage: Int = 1,
    val isLoggedIn: Boolean = false,
    val initializing: Boolean = true,
    val error: String? = null,
    val playedUriStrings: Set<String> = emptySet(),
    val scrollTargetIndex: Int = -1,
    val scrollTargetParentKey: String? = null,
    val userInfo: Pan123UserInfo? = null,
    val orderBy: String = "file_name",
    val orderDirection: String = "asc",
    val pendingAction: String? = null,
    val moveFileId: String? = null,
    val pickerFolders: List<WebDavResource> = emptyList(),
    val pickerIsLoading: Boolean = false,
    val isSearching: Boolean = false,
    val searchQuery: String = "",
    val showAccountDialog: Boolean = false,

    // === 分享链接 ===
    val showShareInputDialog: Boolean = false,
    val shareInputText: String = "",
    val showShareBrowse: Boolean = false,
    val shareKey: String = "",
    val sharePwd: String? = null,
    val shareItems: List<WebDavResource> = emptyList(),
    val shareIsLoading: Boolean = false,
    val shareTotal: Int = 0,
    val shareCurrentParentId: String = "0",
    val shareBreadcrumbs: List<Pan123Breadcrumb> = emptyList(),
    val shareSaveTargetFolderId: String = "0",
    val shareSaveTargetLabel: String = "根目录",
    val shareSaveTargetBreadcrumbs: List<Pan123Breadcrumb> = emptyList(),
    val showShareTargetPicker: Boolean = false,
    val shareTargetPickerFolders: List<WebDavResource> = emptyList(),
    val shareTargetPickerIsLoading: Boolean = false,
    val shareTargetPickerPath: List<Pan123Breadcrumb> = emptyList(),
)
