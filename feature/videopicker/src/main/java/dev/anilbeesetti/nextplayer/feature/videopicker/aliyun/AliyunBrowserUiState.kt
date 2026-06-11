package dev.anilbeesetti.nextplayer.feature.videopicker.aliyun

import dev.anilbeesetti.nextplayer.core.model.WebDavResource

/**
 * 阿里云盘 UI 状态 —— 对应反编译版 AliyunBrowserUiState
 */
data class AliyunBrowserUiState(
    val items: List<WebDavResource> = emptyList(),
    val currentFileId: String = "root",
    val nextMarker: String? = null,
    val breadcrumbs: List<AliyunBreadcrumb> = listOf(AliyunBreadcrumb("根目录", "root")),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isLoggedIn: Boolean = false,
    val reLoginRequired: Boolean = false,
    val error: String? = null,
    val scrollTargetIndex: Int = -1,
    val scrollTargetParentKey: String? = null,
    val orderBy: String = "name:ASC",
    val driveOptions: List<DriveOption> = emptyList(),
    val currentDriveId: String = "",
    val pendingAction: String? = null,
    val moveFileId: String? = null,
    val copyFileId: String? = null,
    val pickerFolders: List<WebDavResource> = emptyList(),
    val pickerIsLoading: Boolean = false
)
