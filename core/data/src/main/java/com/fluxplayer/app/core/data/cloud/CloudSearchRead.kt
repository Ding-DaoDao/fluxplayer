package com.fluxplayer.app.core.data.cloud

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Response

/** 只在搜索读取期间登记网络请求，停止搜索会取消这一批请求，不影响播放和上传。 */
internal class CloudSearchReadScope(private val validate: () -> Unit, private val onRateLimit: () -> Unit) {
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val retries = java.util.concurrent.atomic.AtomicInteger(1)
    private val clients = ConcurrentHashMap<OkHttpClient, OkHttpClient>()

    @Volatile private var stopped = false
    fun register(call: Call) {
        calls.add(call)
        if (stopped) call.cancel()
    }
    fun client(original: OkHttpClient): OkHttpClient = clients.getOrPut(original) {
        original.newBuilder().retryOnConnectionFailure(false).addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            // 避免底层在 Retry-After: 0 时自行重发 503，重试统一消耗目录预算。
            if (response.code == 503 && response.header("Retry-After")?.trim() == "0") response.newBuilder().header("Retry-After", "1").build() else response
        }.build()
    }
    fun cancel() {
        stopped = true
        calls.forEach { it.cancel() }
        calls.clear()
    }

    suspend fun retry(error: Exception): Boolean {
        currentCoroutineContext().ensureActive()
        validate()
        if (error is CancellationException || isCloudSearchLoginFailure(error)) return false
        val status = (error as? CloudSearchHttpException)?.status
            ?: Regex("(?:HTTP\\s*|[（(])(408|425|429|500|502|503|504)", RegexOption.IGNORE_CASE).find(error.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
        val throttled = status == 429 || status == 503 || listOf("频繁", "TooManyRequests", "限流").any { error.message.orEmpty().contains(it, true) }
        if (throttled) onRateLimit()
        if (error !is IOException && status == null && !throttled) return false
        if (status != null && status !in setOf(408, 425, 429, 500, 502, 503, 504)) return false
        if (!retries.compareAndSet(1, 0)) return false
        val retryAfter = (error as? CloudSearchHttpException)?.retryAfter
        val milliseconds = retryAfter?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let { if (it > Long.MAX_VALUE / 1000) Long.MAX_VALUE else it * 1000 }
            ?: retryAfter?.let { runCatching { java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US).parse(it)!!.time - System.currentTimeMillis() }.getOrNull() }
        delay(maxOf(2000, milliseconds ?: 0))
        currentCoroutineContext().ensureActive()
        validate()
        return true
    }

    suspend fun execute(request: () -> Response): Response {
        while (true) {
            currentCoroutineContext().ensureActive()
            validate()
            try {
                val response = request()
                // 登录失效响应交给原客户端刷新会话，刷新失败后才停止搜索。
                if (!response.isSuccessful && response.code !in setOf(401, 403)) {
                    val error = CloudSearchHttpException(response.code, response.header("Retry-After"))
                    response.close()
                    throw error
                }
                return response
            } catch (error: Exception) {
                if (!retry(error)) throw error
            }
        }
    }
    companion object {
        val current = ThreadLocal<CloudSearchReadScope?>()
    }
}

internal class CloudSearchHttpException(val status: Int, val retryAfter: String? = null) : IOException(
    if (status == 401 || status == 403) "登录状态失效（HTTP $status），请重新登录" else "文件夹读取失败（HTTP $status）",
)

internal fun isCloudSearchLoginFailure(error: Throwable): Boolean =
    (error is CloudSearchHttpException && error.status in setOf(401, 403)) ||
        error.javaClass.simpleName.contains("TokenExpired") ||
        listOf("登录", "账号已变化", "账号已切换", "未授权", "unauthorized", "HTTP 401", "HTTP 403").any { error.message.orEmpty().contains(it, true) }

/** 每个目录共享一次读取重试预算；等待期间仍可立即取消。 */
internal suspend fun <T> cloudSearchRead(validate: () -> Unit, onRateLimit: () -> Unit, read: suspend () -> T): T {
    val scope = CloudSearchReadScope(validate, onRateLimit)
    val cancellation = Job(currentCoroutineContext()[Job])
    cancellation.invokeOnCompletion { scope.cancel() }
    try {
        return withContext(CloudSearchReadScope.current.asContextElement(scope)) {
            suspend fun attempt(): T {
                currentCoroutineContext().ensureActive()
                validate()
                return read().also {
                    currentCoroutineContext().ensureActive()
                    validate()
                }
            }
            try {
                attempt()
            } catch (error: Exception) {
                if (!scope.retry(error)) throw error
                attempt()
            }
        }
    } finally {
        cancellation.complete()
    }
}
