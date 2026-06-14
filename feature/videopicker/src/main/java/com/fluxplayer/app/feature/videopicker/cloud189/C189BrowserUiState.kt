package com.fluxplayer.app.feature.videopicker.cloud189

import com.fluxplayer.app.core.model.WebDavResource

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
    val scrollTargetIndex: Int = -1,
    val scrollTargetParentKey: String? = null,
    val loginLoading: Boolean = false,
    /** 0=密码登录, 1=短信登录 */
    val loginTab: Int = 0,
    /** 验证码是否已发送 */
    val smsCodeSent: Boolean = false,
    /** 正在发送验证码 */
    val smsSending: Boolean = false,
    /** 短信已发送提示文本 */
    val smsSentMessage: String? = null,
    val pendingAction: String? = null,
    val moveFileId: String? = null,
    val pickerFolders: List<WebDavResource> = emptyList(),
    val pickerIsLoading: Boolean = false,
    val playedUriSet: Set<String> = emptySet(),

    // === 分享 ===
    val showShareInputDialog: Boolean = false,
    val shareInputText: String = "",

    // === 用户信息 ===
    val showUserInfoDialog: Boolean = false,
    val userNickname: String = "",
    val userPhone: String = "",
    val userCapacity: Long = 0,
    val userAvailable: Long = 0,
    val userVipExpireTime: String = "",

    // === 分享浏览 ===
    val showShareBrowse: Boolean = false,
    val shareBrowseKey: String = "",
    val shareBrowsePwd: String = "",
    val shareBrowseShareId: String = "",
    val shareSaveTargetFolderId: String = "-11",
    val shareSaveTargetFolderLabel: String = "根目录",
    val showShareTargetPicker: Boolean = false,
    val shareTargetPickerFolders: List<WebDavResource> = emptyList(),
    val shareTargetPickerIsLoading: Boolean = false,
    val shareTargetPickerPath: List<C189Breadcrumb> = emptyList(),
    val shareSaveTargetBreadcrumbs: List<C189Breadcrumb> = emptyList(),

    // === 分享弹窗独立状态（不污染主界面 items/breadcrumbs） ===
    val shareItems: List<WebDavResource> = emptyList(),
    val shareBreadcrumbs: List<C189Breadcrumb> = emptyList(),
    val shareName: String = "",
    val shareCurrentFileId: String = "",
    val shareIsFolder: Boolean = false,
    val shareMode: Int = 0,
    val shareIsLoading: Boolean = false,
    val shareHasMore: Boolean = true,
    val shareCurrentPage: Int = 1,
)
