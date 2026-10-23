package com.fluxplayer.app.core.data.cloud

import org.json.JSONObject

/** 按天翼当前网页端的普通上传流程构造参数；覆盖上传必须由用户另外选择。 */
internal object TianyiUploadProtocol {
    fun initialize(parent: String, name: String, size: Long, chunk: Long, hash: String, sliceHash: String): Map<String, String> = linkedMapOf(
        "parentFolderId" to parent.ifBlank { "-11" },
        "fileName" to encodeName(name),
        "fileSize" to size.toString(),
        "sliceSize" to chunk.toString(),
    ).apply {
        if (size <= chunk) {
            put("fileMd5", hash)
            put("sliceMd5", sliceHash)
        } else {
            put("lazyCheck", "1")
        }
    }

    fun commit(uploadId: String, hash: String, sliceHash: String, parts: Int): Map<String, String> = linkedMapOf(
        "uploadFileId" to uploadId,
        "lazyCheck" to if (parts > 1) "1" else "0",
        "fileMd5" to hash,
        "sliceMd5" to sliceHash,
    )

    fun headers(raw: String): Map<String, String> {
        // 当前官方响应直接返回键值对；兼容旧响应把整个字符串百分号编码的形式。
        val decoded = if ('=' in raw.substringBefore('&')) raw else java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
        return decoded.split('&').mapNotNull { pair ->
            val position = pair.indexOf('=')
            if (position > 0) pair.take(position) to pair.substring(position + 1) else null
        }.toMap()
    }

    fun completedId(response: JSONObject): String = listOf(
        response.optJSONObject("file")?.optString("userFileId"),
        response.optJSONObject("file")?.optString("fileId"),
        response.optJSONObject("data")?.optString("fileId"),
    ).firstOrNull { !it.isNullOrBlank() && it != "null" }.orEmpty()

    private fun encodeName(name: String): String = java.net.URLEncoder.encode(name, "UTF-8")
        .replace("+", "%20").replace("%21", "!").replace("%27", "'")
        .replace("%28", "(").replace("%29", ")").replace("%7E", "~")
}
