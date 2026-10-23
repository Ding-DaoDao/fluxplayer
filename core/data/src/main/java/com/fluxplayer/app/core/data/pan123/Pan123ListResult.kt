package com.fluxplayer.app.core.data.pan123

/**
 * 自己网盘列目录的结果。
 *
 * 列表接口按 wenxi 的方式用 `next` 游标翻页：请求带 `next` 与 `Page=1`，
 * 响应的 `Next` 是下一页游标；`-1` 或缺失表示没有更多。
 */
data class Pan123ListResult(
    val items: List<Pan123FileItem>,
    /** 下一页游标；null 表示已读完。 */
    val nextCursor: String? = null,
) {
    val hasMore: Boolean
        get() = nextCursor != null && items.isNotEmpty()

    companion object {
        /** 与 listFiles 请求中的 limit 保持一致。 */
        const val PAGE_SIZE = 100
    }
}
