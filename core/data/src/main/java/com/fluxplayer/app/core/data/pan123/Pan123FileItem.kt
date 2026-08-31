package com.fluxplayer.app.core.data.pan123

import org.json.JSONObject

data class Pan123FileItem(
    val fileId: String,
    val fileName: String,
    val type: Int,
    val size: Long,
    val category: Int = 0,
    val etag: String,
    val s3keyFlag: String,
    val downloadUrl: String,
    val createAt: String,
    val trashedAt: String,
    val starredStatus: Int,
    val thumbnailUrl: String? = null,
    /**
     * 列表接口返回的原始 JSON 对象（含 Pid/Status/Category/CreateAt 等全部字段）。
     * 删除（file/trash）时服务端要求提交完整对象，与海阔视界 main.js 的
     * recycleDeleteFile(data) 行为一致：data 为列表原始条目原样提交。
     */
    val raw: JSONObject? = null
) {
    val isDirectory: Boolean get() = type == 1
    val isVideo: Boolean get() = Regex("\\.(mp4|mkv|avi|rmvb|mov|flv|wmv|webm|m4v|ts)$", RegexOption.IGNORE_CASE).containsMatchIn(fileName)
    val isImage: Boolean get() = Regex("\\.(jpg|jpeg|png|webp|gif|bmp)$", RegexOption.IGNORE_CASE).containsMatchIn(fileName)
}
