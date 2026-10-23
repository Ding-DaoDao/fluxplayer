package com.fluxplayer.app.core.data.pan123

/**
 * 自己网盘列目录的结果。
 *
 * 分页有两套并存的口径，两边都有实测依据：
 * - `page` 页码模式：自己网盘列表（docs/pan123-api.md，2026-10-05 真机验证）只认小写 `page`，
 *   响应不带 `Next`；
 * - `next` 游标模式：wenxi 的 `pan123.dart` 用 `next` + 响应 `Next` 翻页。
 *
 * 因此 [nextCursor] 只在服务端真的下发了可用游标时才有值；此时按游标翻页，
 * 否则退回「满一页才继续」的页码判定。
 */
data class Pan123ListResult(
    val items: List<Pan123FileItem>,
    /** 下一页游标（响应的 `Next`）；`null` 表示没有可用游标。 */
    val nextCursor: String? = null,
) {
    val hasMore: Boolean
        get() = nextCursor != null || items.size >= PAGE_SIZE

    companion object {
        /** 与 listFiles 请求中的 limit 保持一致。 */
        const val PAGE_SIZE = 100

        /** 响应里的 `Next` 是不是可用游标：`0`/`-1`/空都表示没有下一页。 */
        fun usableCursor(raw: String?): String? =
            raw?.takeIf { it.isNotBlank() && it != "null" && it != "-1" && it != "0" }
    }
}
