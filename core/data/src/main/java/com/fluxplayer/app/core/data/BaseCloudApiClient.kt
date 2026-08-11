package com.fluxplayer.app.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

abstract class BaseCloudApiClient(
    protected val client: OkHttpClient = CloudHttpClient.DEFAULT
) {
    protected val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    protected val formUrlEncoded = "application/x-www-form-urlencoded".toMediaType()

    protected suspend fun executeRequest(request: Request): String {
        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string()
                    ?: throw IllegalStateException("Empty response from ${request.url}")
                if (!resp.isSuccessful) {
                    throw IllegalStateException("HTTP ${resp.code} from ${request.url}: ${body.take(200)}")
                }
                body
            }
        }
    }

    protected suspend fun executeRequestOrNull(request: Request): String? {
        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { resp ->
                resp.body?.string()
            }
        }
    }

    /**
     * 注意：返回的 [Response] 由调用方负责关闭，务必用 `.use { }` 包裹，否则会泄漏连接。
     */
    protected suspend fun executeRequestAndGetResponse(request: Request): Response {
        return withContext(Dispatchers.IO) {
            client.newCall(request).execute()
        }
    }

    protected fun buildUrl(base: String, params: Map<String, String>): String {
        if (params.isEmpty()) return base
        val sep = if ("?" in base) "&" else "?"
        return base + sep + params.entries.joinToString("&") { "${it.key}=${it.value}" }
    }
}
