package com.fluxplayer.app.core.tingshu

import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 仅在明确打开阿里云盘登录页时读取该站点的 token，不在其他书源网页执行。 */
object AliyunWebLogin {
    private val hosts = setOf("www.alipan.com", "alipan.com")

    fun isLoginUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host?.lowercase() in hosts && uri.userInfo == null && uri.port in setOf(-1, 443)
    }.getOrDefault(false)

    fun canReadToken(loginUrl: String, currentUrl: String): Boolean =
        isLoginUrl(loginUrl) && isLoginUrl(currentUrl) && runCatching {
            val path = URI(currentUrl).path.orEmpty()
            path == "/drive" || path.startsWith("/drive/")
        }.getOrDefault(false)

    /** WebView 将 JS 返回的字符串再次 JSON 编码，不能用 trim('"') 解码。 */
    fun tokenFromJavascript(result: String): String? = runCatching {
        require(result.length <= 256 * 1024)
        val encoded = requireNotNull(Json.parseToJsonElement(result) as? JsonPrimitive)
        require(encoded.isString)
        val token = requireNotNull(Json.parseToJsonElement(encoded.content) as? JsonObject)
        require((token["access_token"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true)
        token.toString()
    }.getOrNull()

    fun tokenWithRequestHeaders(tokenJson: String, authorization: String, deviceId: String, signature: String): String {
        val token = (Json.parseToJsonElement(tokenJson) as JsonObject).toMutableMap()
        // 只有请求与当前账号令牌完全匹配时才合并设备信息，避免账号切换时串用。
        if (authorization == "Bearer ${(token["access_token"] as? JsonPrimitive)?.content}") {
            if (deviceId.isNotBlank()) token["device_id"] = JsonPrimitive(deviceId)
            if (signature.isNotBlank()) token["signature"] = JsonPrimitive(signature)
        }
        return JsonObject(token).toString()
    }
}
