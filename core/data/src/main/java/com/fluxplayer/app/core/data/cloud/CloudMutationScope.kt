package com.fluxplayer.app.core.data.cloud

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** 批量写操作禁止底层重发，取消时关闭本次操作仍在等待的连接。 */
internal class CloudMutationScope {
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val clients = ConcurrentHashMap<OkHttpClient, OkHttpClient>()

    @Volatile private var stopped = false

    fun execute(original: OkHttpClient, request: Request): Response {
        val client = clients.getOrPut(original) {
            original.newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                .addNetworkInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    if (response.code == 503 && response.header("Retry-After")?.trim() == "0") response.newBuilder().header("Retry-After", "1").build() else response
                }.build()
        }
        val call = client.newCall(request)
        calls.add(call)
        if (stopped) call.cancel()
        return call.execute()
    }

    fun cancel() {
        stopped = true
        calls.forEach { it.cancel() }
        calls.clear()
    }

    companion object {
        val current = ThreadLocal<CloudMutationScope?>()
    }
}

internal suspend fun <T> cloudMutation(action: suspend () -> T): T {
    val scope = CloudMutationScope()
    val lifetime = Job(currentCoroutineContext()[Job])
    lifetime.invokeOnCompletion { scope.cancel() }
    try {
        return withContext(CloudMutationScope.current.asContextElement(scope)) {
            currentCoroutineContext().ensureActive()
            action()
        }
    } finally {
        lifetime.complete()
    }
}
