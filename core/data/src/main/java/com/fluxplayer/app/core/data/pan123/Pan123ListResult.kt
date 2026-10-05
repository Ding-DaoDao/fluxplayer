package com.fluxplayer.app.core.data.pan123

/**
 * 自己网盘列目录的结果。
 *
 * 注意：自己网盘的 list 接口响应里**没有** `Next` 字段（那是分享接口才有的游标），
 * 所以 [nextCursor] 正常情况下恒为 null。保留该字段仅为兼容异常响应，
 * 判断是否还有下一页一律以 [hasMore] 为准。
 */
data class Pan123ListResult(
    val items: List<Pan123FileItem>,
    // Web API 的 Next："0" 表示还有后续；null 表示响应未提供该字段。
    val nextCursor: String? = null,
) {
    /**
     * 服务端会无视 limit 参数直接返回整个目录（上限约 500 项），
     * 因此用「是否刚好满一页」判断还有下一页：满 100 条才继续翻页。
     */
    val hasMore: Boolean
        get() = items.size >= PAGE_SIZE

    companion object {
        /** 与 listFiles 请求中的 limit 保持一致。 */
        const val PAGE_SIZE = 100
    }
}
