package com.fluxplayer.app.feature.videopicker

import com.fluxplayer.app.core.model.WebDavResource

/**
 * 导航栈中每一层目录的状态接口。
 * 所有 provider（Aliyun、Quark、C189、Pan123、Yun139、OpenList、WebDAV）
 * 的导航栈条目实现此接口，以便 [CloudBrowserPanel] 统一渲染。
 */
interface DirectoryState {
    /** Compose key，用于保持滚动位置 */
    val key: String
    val items: List<WebDavResource>
    val isLoading: Boolean
    val error: String?
}
