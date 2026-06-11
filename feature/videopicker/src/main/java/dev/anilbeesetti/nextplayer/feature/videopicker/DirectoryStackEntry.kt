package dev.anilbeesetti.nextplayer.feature.videopicker

import dev.anilbeesetti.nextplayer.core.model.WebDavResource

/**
 * 导航栈中每一层目录的状态。
 * 所有 provider（Aliyun、Quark、C189、Pan123、Yun139、OpenList、WebDAV）共用此类型。
 */
data class DirectoryStackEntry(
    val fileId: String,
    val label: String,
    val items: List<WebDavResource> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)
