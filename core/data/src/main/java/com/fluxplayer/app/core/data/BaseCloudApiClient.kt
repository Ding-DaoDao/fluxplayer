package com.fluxplayer.app.core.data

import com.fluxplayer.app.core.data.cloud.CloudMutationScope
import com.fluxplayer.app.core.data.cloud.CloudSearchReadScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

abstract class BaseCloudApiClient(
    protected val client: OkHttpClient = CloudHttpClient.DEFAULT,
) {
    internal val featureHttpClient: OkHttpClient get() = client
    protected val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    protected val formUrlEncoded = "application/x-www-form-urlencoded".toMediaType()

    protected suspend fun executeRequest(request: Request): String {
        return withContext(Dispatchers.IO) {
            searchAwareExecute(request).use { resp ->
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
            searchAwareExecute(request).use { resp ->
                resp.body?.string()
            }
        }
    }

    /**
     * 注意：返回的 [Response] 由调用方负责关闭，务必用 `.use { }` 包裹，否则会泄漏连接。
     */
    protected suspend fun executeRequestAndGetResponse(request: Request): Response {
        return withContext(Dispatchers.IO) {
            searchAwareExecute(request)
        }
    }

    private suspend fun searchAwareExecute(request: Request): Response {
        CloudMutationScope.current.get()?.let { return it.execute(client, request) }
        val scope = CloudSearchReadScope.current.get()
        fun send(): Response {
            val call = (scope?.client(client) ?: client).newCall(request)
            scope?.register(call)
            return call.execute()
        }
        return if (scope == null) send() else scope.execute(::send)
    }

    protected fun buildUrl(base: String, params: Map<String, String>): String {
        if (params.isEmpty()) return base
        val sep = if ("?" in base) "&" else "?"
        return base + sep + params.entries.joinToString("&") { "${it.key}=${it.value}" }
    }
}
