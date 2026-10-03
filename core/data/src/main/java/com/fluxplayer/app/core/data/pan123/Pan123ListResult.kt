package com.fluxplayer.app.core.data.pan123

data class Pan123ListResult(
    val items: List<Pan123FileItem>,
    // Web API 的 Next："-1" 表示结束；null 表示响应未提供该字段。
    val nextCursor: String?,
) {
    val hasMore: Boolean
        get() = items.isNotEmpty() && (nextCursor?.let { it != "-1" } ?: (items.size >= 100))
}
