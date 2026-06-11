package dev.anilbeesetti.nextplayer.feature.videopicker.pan123

import dev.anilbeesetti.nextplayer.core.data.pan123.Pan123FileItem
import dev.anilbeesetti.nextplayer.core.data.pan123.Pan123UserInfo
import dev.anilbeesetti.nextplayer.core.model.WebDavResource

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
    val error: String? = null,
    val playedUriStrings: Set<String> = emptySet(),
    val scrollTargetIndex: Int = -1,
    val scrollTargetParentKey: String? = null,
    val userInfo: Pan123UserInfo? = null,
    val orderBy: String = "file_name",
    val orderDirection: String = "asc",
    val pendingAction: String? = null,
    val moveFileId: String? = null,
    val copyFileItem: Pan123FileItem? = null,
    val pickerFolders: List<WebDavResource> = emptyList(),
    val pickerIsLoading: Boolean = false
)
