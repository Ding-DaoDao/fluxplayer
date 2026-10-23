package com.fluxplayer.app.core.data.cloud

import org.json.JSONArray
import org.json.JSONObject

data class CloudShareOptions(val title: String, val expiryDays: Int? = null, val password: String = "")

data class CloudCreatedShare(val url: String, val password: String)

data class CloudRemoteFile(
    val id: String,
    val name: String,
    val directory: Boolean,
    val size: Long = 0,
    val token: String = "",
    val parentId: String = "",
)

data class CloudShareSession(
    val id: String,
    val password: String,
    val token: String = "",
    val rootId: String = "0",
)

data class CloudSpace(val id: String, val name: String)

internal fun jsonObject(vararg values: Pair<String, Any?>): JSONObject = JSONObject().apply {
    values.forEach { (key, value) -> put(key, if (value is Collection<*>) JSONArray(value) else value) }
}

internal fun JSONObject.objects(key: String): List<JSONObject> {
    val array = optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).map { array.getJSONObject(it) }
}

internal fun JSONObject.requiredString(key: String): String = optString(key).takeIf { it.isNotBlank() && it != "null" }
    ?: error("网盘响应缺少 $key，请刷新后重试")
