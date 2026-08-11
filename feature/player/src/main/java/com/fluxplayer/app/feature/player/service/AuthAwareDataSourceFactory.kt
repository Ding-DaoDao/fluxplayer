package com.fluxplayer.app.feature.player.service

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import com.fluxplayer.app.core.common.CloudPlayHeaders
import com.fluxplayer.app.core.data.openlist.OpenListTokenProvider
import com.fluxplayer.app.core.data.aliyun.AliyunAuthProvider
import com.fluxplayer.app.core.data.cloud189.C189AuthProvider
import com.fluxplayer.app.core.data.pan123.Pan123AuthProvider
import com.fluxplayer.app.core.data.quark.QuarkAuthProvider
import com.fluxplayer.app.core.data.yun139.Yun139AuthProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.AssetDataSource
import androidx.media3.datasource.ContentDataSource
import androidx.media3.datasource.TransferListener

/**
 * DataSource.Factory 包装 —— 对 HTTP/HTTPS URI 从 userinfo（user:pass@host）
 * 提取 Basic 认证信息转为 Authorization header；对非 HTTP URI 回退到本地数据源。
 *
 * ExoPlayer 默认的 HTTP 栈不支持 user:pass@ 嵌入式认证，这个工厂
 * 在请求发送前拦截并设置 header，同时剥离 URI 中的敏感信息。
 */
class AuthAwareDataSourceFactory(
    private val context: Context,
    userAgent: String = "NextPlayer",
) : DataSource.Factory {

    private val httpFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(userAgent)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(20_000)
        .setAllowCrossProtocolRedirects(true)

    override fun createDataSource(): DataSource {
        if (QuarkAuthProvider.isActive) {
            httpFactory.setUserAgent(QuarkAuthProvider.userAgent)
        }
        return AuthAwareDataSource(
            httpDelegate = httpFactory.createDataSource(),
            fileDelegate = FileDataSource(),
            contentDelegate = ContentDataSource(context),
            assetDelegate = AssetDataSource(context),
        )
    }
}

/**
 * 复合 DataSource — 根据 URI scheme 选择 HTTP 或本地数据源。
 * 包含重试逻辑，临时网络故障自动恢复。
 */
private class AuthAwareDataSource(
    private val httpDelegate: HttpDataSource,
    private val fileDelegate: DataSource,
    private val contentDelegate: DataSource,
    private val assetDelegate: DataSource,
) : DataSource {

    private var activeDelegate: DataSource? = null

    companion object {
        private const val TAG = "AuthAwareDataSource"

        /** 重试间隔基础值（毫秒） */
        private const val RETRY_DELAY_MS = 300L
    }

    override fun addTransferListener(transferListener: TransferListener) {
        httpDelegate.addTransferListener(transferListener)
        fileDelegate.addTransferListener(transferListener)
        contentDelegate.addTransferListener(transferListener)
        assetDelegate.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        val scheme = uri.scheme ?: ""

        Log.d(TAG, "========== open START ==========")
        Log.d(TAG, "open: scheme=$scheme")
        Log.d(TAG, "open: uri=${uri.toString().take(200)}...")
        Log.d(TAG, "open: position=${dataSpec.position}, length=${dataSpec.length}")

        val (delegate, effectiveSpec) = when (scheme) {
            "http", "https" -> {
                applyHttpAuth(httpDelegate, uri)
                applyCloudPlayHeaders(httpDelegate, uri)
                val strippedSpec = stripUserInfo(dataSpec, uri)
                httpDelegate to strippedSpec
            }
            ContentResolver.SCHEME_FILE -> fileDelegate to dataSpec
            ContentResolver.SCHEME_CONTENT -> contentDelegate to dataSpec
            "asset" -> assetDelegate to dataSpec
            "android.resource" -> contentDelegate to dataSpec
            else -> fileDelegate to dataSpec
        }
        activeDelegate = delegate
        return openWithRetry(delegate, effectiveSpec, maxAttempts = 2)
    }

    /** 带重试的 open 调用，最多重试 maxAttempts 次 */
    private fun openWithRetry(delegate: DataSource, dataSpec: DataSpec, maxAttempts: Int): Long {
        var lastException: Exception? = null
        for (attempt in 0 until maxAttempts) {
            try {
                val result = delegate.open(dataSpec)
                Log.d(TAG, "openWithRetry: success attempt=$attempt result=$result")
                return result
            } catch (e: HttpDataSource.InvalidResponseCodeException) {
                lastException = e
                Log.w(TAG, "openWithRetry: attempt=$attempt HTTP ${e.responseCode}")
                // 416 on seek: throw immediately so ExoPlayer can handle it fast
                if (e.responseCode == 416 && dataSpec.position > 0) throw e
                if (attempt < maxAttempts - 1) {
                    if (Thread.currentThread().isInterrupted) throw e
                    try {
                        // 短延迟重试；响应线程中断（ExoPlayer release 时会中断加载线程）
                        Thread.sleep((attempt + 1) * RETRY_DELAY_MS)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw e
                    }
                } else {
                    throw e
                }
            } catch (e: HttpDataSource.HttpDataSourceException) {
                lastException = e
                Log.w(TAG, "openWithRetry: attempt=$attempt ${e.javaClass.simpleName} ${e.message}")
                if (attempt < maxAttempts - 1) {
                    if (Thread.currentThread().isInterrupted) throw e
                    try {
                        Thread.sleep((attempt + 1) * RETRY_DELAY_MS)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw e
                    }
                } else {
                    throw e
                }
            }
        }
        throw lastException ?: IllegalStateException("open failed for: $dataSpec")
    }

    /** 注入云盘播放所需的 Cookie/Referer/User-Agent 等 header。 */
    private fun applyCloudPlayHeaders(http: HttpDataSource, uri: Uri) {
        val host = uri.host ?: return
        Log.d(TAG, "applyCloudPlayHeaders: host=$host, uri=${uri.toString().take(150)}...")
        
        if (CloudPlayHeaders.isSelfAuthenticatingUrl(uri.toString())) {
            Log.d(TAG, "applyCloudPlayHeaders: URL is self-authenticating, skipping")
            return
        }
        
        val headers = CloudPlayHeaders.getHeaders(host)
        Log.d(TAG, "applyCloudPlayHeaders: Found ${headers.size} headers for host=$host")
        
        for ((key, value) in headers) {
            http.setRequestProperty(key, value)
            // 不打印 value：Authorization/Cookie 等敏感信息不应进入 logcat
            Log.d(TAG, "applyCloudPlayHeaders: Set header $key (value hidden)")
        }
    }

    /** 在 HTTP 请求上设置认证 header。 */
    private fun applyHttpAuth(http: HttpDataSource, uri: Uri) {
        val fragment = uri.fragment ?: ""
        val host = uri.host ?: ""
        val url = uri.toString().take(200)
        
        Log.d(TAG, "========== applyHttpAuth START ==========")
        Log.d(TAG, "applyHttpAuth: host=$host, fragment=$fragment")
        Log.d(TAG, "applyHttpAuth: url=${url}...")

        // 夸克/UC 播放认证：通过 URI fragment 检测
        if (("quarkPlay" in fragment || "ucPlay" in fragment) && QuarkAuthProvider.isActive) {
            Log.d(TAG, "applyHttpAuth: Applying Quark/UC auth")
            http.setRequestProperty("Cookie", QuarkAuthProvider.cookie)
            http.setRequestProperty("Referer", QuarkAuthProvider.referer)
            http.setRequestProperty("User-Agent", QuarkAuthProvider.userAgent)
            // 不打印 Cookie 值（敏感信息）
            return
        }

        // 阿里云盘播放认证
        if ("alipanPlay" in fragment && AliyunAuthProvider.isActive) {
            Log.d(TAG, "applyHttpAuth: Applying Aliyun auth")
            http.setRequestProperty("Authorization", AliyunAuthProvider.authorization)
            http.setRequestProperty("Referer", "https://www.alipan.com/")
            http.setRequestProperty("User-Agent", AliyunAuthProvider.userAgent)
            return
        }

        // 123 云盘播放认证：video CDN 自带签名，只需基础 Referer + UA
        if ("pan123Play" in fragment) {
            Log.d(TAG, "applyHttpAuth: pan123Play detected in fragment")
            Log.d(TAG, "applyHttpAuth: Pan123AuthProvider.isActive=${Pan123AuthProvider.isActive}")
            Log.d(TAG, "applyHttpAuth: Pan123AuthProvider.referer=${Pan123AuthProvider.referer}")
            Log.d(TAG, "applyHttpAuth: Pan123AuthProvider.userAgent=${Pan123AuthProvider.userAgent}")
            
            if (Pan123AuthProvider.isActive) {
                Log.d(TAG, "applyHttpAuth: Applying Pan123 auth - setting headers")
                http.setRequestProperty("Referer", Pan123AuthProvider.referer)
                http.setRequestProperty("User-Agent", Pan123AuthProvider.userAgent)
                http.setRequestProperty("X-MF-PAN-RANGE", "1")
                Log.d(TAG, "applyHttpAuth: Pan123 headers SET - Referer=${Pan123AuthProvider.referer}, User-Agent=${Pan123AuthProvider.userAgent}")
                Log.d(TAG, "========== applyHttpAuth END (pan123) ==========")
                return
            } else {
                Log.e(TAG, "applyHttpAuth: pan123Play detected but Pan123AuthProvider.isActive=FALSE!")
            }
        }

        if ("pan123Play" in fragment && !Pan123AuthProvider.isActive) {
            Log.w(TAG, "pan123Play detected but Pan123AuthProvider.isActive=false!")
        }

        // 天翼云盘播放认证
        if ("189Play" in fragment && C189AuthProvider.isActive) {
            http.setRequestProperty("Cookie", "COOKIE_LOGIN_USER=${C189AuthProvider.accessToken}")
            http.setRequestProperty("User-Agent", C189AuthProvider.userAgent)
            return
        }

        // 移动云盘播放认证
        if ("yun139Play" in fragment && Yun139AuthProvider.isActive) {
            http.setRequestProperty("Authorization", Yun139AuthProvider.authorization)
            http.setRequestProperty("x-yun-device-id", Yun139AuthProvider.deviceInfo)
            http.setRequestProperty("x-yun-client-info", Yun139AuthProvider.deviceInfo)
            http.setRequestProperty("x-yun-api-version", "v2")
            http.setRequestProperty("x-yun-svc-type", "1")
            http.setRequestProperty("x-yun-module-type", "100")
            http.setRequestProperty("x-yun-app-channel", "10000023")
            return
        }

        val userInfo = uri.userInfo
        if (userInfo.isNullOrEmpty()) {
            val token = OpenListTokenProvider.bearerToken
            if (token != null && uri.host == "127.0.0.1" && uri.port == 5244) {
                http.setRequestProperty("Authorization", "Bearer $token")
            }
            // HLS .ts 分片兜底：当 fragment 和 CloudPlayHeaders 域名均未命中时，
            // 为当前 active 的云盘注入基础认证头，防止 CDN 边缘节点拒绝请求。
            // 由于 resolve 时已执行 clearOtherProviders()，同一时刻只有一个 Provider active。
            if (QuarkAuthProvider.isActive) {
                http.setRequestProperty("Cookie", QuarkAuthProvider.cookie)
                http.setRequestProperty("User-Agent", QuarkAuthProvider.userAgent)
            } else if (AliyunAuthProvider.isActive) {
                http.setRequestProperty("Authorization", AliyunAuthProvider.authorization)
                http.setRequestProperty("Referer", "https://www.alipan.com/")
                http.setRequestProperty("User-Agent", AliyunAuthProvider.userAgent)
            } else if (Pan123AuthProvider.isActive) {
                Log.d(TAG, "applyHttpAuth: FALLBACK - Pan123AuthProvider.isActive, setting headers")
                Log.d(TAG, "applyHttpAuth: FALLBACK - referer=${Pan123AuthProvider.referer}, ua=${Pan123AuthProvider.userAgent}")
                http.setRequestProperty("Referer", Pan123AuthProvider.referer)
                http.setRequestProperty("User-Agent", Pan123AuthProvider.userAgent)
                http.setRequestProperty("X-MF-PAN-RANGE", "1")
                Log.d(TAG, "applyHttpAuth: FALLBACK - Pan123 headers SET")
            } else if (C189AuthProvider.isActive) {
                http.setRequestProperty("Cookie", "COOKIE_LOGIN_USER=${C189AuthProvider.accessToken}")
                http.setRequestProperty("User-Agent", C189AuthProvider.userAgent)
            } else if (Yun139AuthProvider.isActive) {
                http.setRequestProperty("Authorization", Yun139AuthProvider.authorization)
                http.setRequestProperty("x-yun-device-id", Yun139AuthProvider.deviceInfo)
                http.setRequestProperty("x-yun-api-version", "v2")
            }
            return
        }
        val encoded = Base64.encodeToString(
            userInfo.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )
        http.setRequestProperty("Authorization", "Basic $encoded")
    }

    /** 从 DataSpec 的 URI 中剥离 userinfo，避免敏感信息泄露。 */
    private fun stripUserInfo(dataSpec: DataSpec, uri: Uri): DataSpec {
        val userInfo = uri.userInfo
        if (userInfo.isNullOrEmpty()) return dataSpec
        val authority = uri.host + if (uri.port != -1) ":${uri.port}" else ""
        val cleanUri = uri.buildUpon()
            .encodedAuthority(authority)
            .build()
        return dataSpec.buildUpon()
            .setUri(cleanUri)
            .build()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return activeDelegate?.read(buffer, offset, length)
            ?: error("No active data source — call open() first")
    }

    override fun getUri(): Uri? = activeDelegate?.uri

    override fun close() {
        activeDelegate?.close()
        activeDelegate = null
        httpDelegate.close()
        fileDelegate.close()
        contentDelegate.close()
        assetDelegate.close()
    }

    @UnstableApi
    override fun getResponseHeaders(): Map<String, List<String>> =
        (activeDelegate as? HttpDataSource)?.responseHeaders ?: emptyMap()
}
