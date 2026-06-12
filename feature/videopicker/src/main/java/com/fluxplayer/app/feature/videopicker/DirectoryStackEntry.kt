package com.fluxplayer.app.feature.videopicker

import com.fluxplayer.app.core.model.WebDavResource

/**
 * 导航栈中每一层目录的状态。
 * 所有 provider（Aliyun、Quark、C189、Pan123、Yun139、OpenList）共用此类型。
 */
data class DirectoryStackEntry(
    val fileId: String,
    val label: String,
    override val items: List<WebDavResource> = emptyList(),
    override val isLoading: Boolean = true,
    override val error: String? = null,
) : DirectoryState {
    override val key: String get() = fileId
}
