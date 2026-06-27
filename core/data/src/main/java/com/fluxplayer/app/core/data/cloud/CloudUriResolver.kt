package com.fluxplayer.app.core.data.cloud

import android.content.Context
import android.net.Uri
import android.util.Log
import com.fluxplayer.app.core.common.CloudPlayHeaders
import com.fluxplayer.app.core.common.CloudPlaylistCache
import com.fluxplayer.app.core.common.CloudUriScheme
import com.fluxplayer.app.core.common.Pan123FallbackCache
import com.fluxplayer.app.core.common.VideoQualityCache
import com.fluxplayer.app.core.data.aliyun.AliyunApiClient
import com.fluxplayer.app.core.data.aliyun.AliyunAuthProvider
import com.fluxplayer.app.core.data.cloud189.C189ApiClient
import com.fluxplayer.app.core.data.GlobalCookieJar
import com.fluxplayer.app.core.data.cloud189.C189AuthProvider
import com.fluxplayer.app.core.data.pan123.Pan123ApiClient
import com.fluxplayer.app.core.data.pan123.Pan123AuthProvider
import com.fluxplayer.app.core.data.quark.QuarkApiClient
import com.fluxplayer.app.core.data.quark.QuarkAuthProvider
import com.fluxplayer.app.core.data.yun139.Yun139ApiClient
import com.fluxplayer.app.core.data.yun139.Yun139AuthProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudUriResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val videoQualityCache: VideoQualityCache
) {
    companion object {
        private const val TAG = "CloudUriResolver"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 清除除指定 Provider 之外的所有云盘认证状态，防止 HLS 分片兜底注入时 Cookie/Token 串号 */
    private fun clearOtherProviders(except: String) {
        if (except != "quark") {
            QuarkAuthProvider.clear()
            GlobalCookieJar.clearHost("drive.quark.cn")
            GlobalCookieJar.clearHost("pc-api.uc.cn")
            GlobalCookieJar.clearHost("drive.uc.cn")
        }
        if (except != "alipan") {
            AliyunAuthProvider.clear()
            GlobalCookieJar.clearHost("api.alipan.com")
            GlobalCookieJar.clearHost("www.alipan.com")
        }
        if (except != "pan123") {
            Pan123AuthProvider.clear()
            GlobalCookieJar.clearHost("api.123278.com")
            GlobalCookieJar.clearHost("apigate.123795.com")
        }
        if (except != "cloud189") {
            C189AuthProvider.clear()
            GlobalCookieJar.clearHost("cloud.189.cn")
            GlobalCookieJar.clearHost("api.cloud.189.cn")
            GlobalCookieJar.clearHost("m.cloud.189.cn")
            GlobalCookieJar.clearHost("open.e.189.cn")
        }
        if (except != "yun139") {
            Yun139AuthProvider.clear()
            GlobalCookieJar.clearHost("yun.139.com")
            GlobalCookieJar.clearHost("api.139.com")
        }
    }

    suspend fun resolve(uri: Uri): Uri? {
        if (!CloudUriScheme.isCloudUri(uri)) return null
        val provider = CloudUriScheme.getProvider(uri) ?: return null
        val fileId = CloudUriScheme.getFileId(uri) ?: return null

        // Check cache first
        val cachedUrl = CloudPlaylistCache.getResolvedUrl(provider, fileId)
        if (cachedUrl != null) {
            // 缓存命中但画质选项缺失时，异步补缓存（不阻塞本次播放）
            // 场景：首次播放时 getVideoPlayInfo 失败走了下载兜底，
            // CloudPlaylistCache 存了兜底 URL，15min 内 resolvePan123 不会再被调用
            if (provider == "pan123" && !videoQualityCache.hasQualityOptions(provider, fileId)) {
                Log.d(TAG, "resolve: CloudPlaylistCache hit but quality options missing for $fileId, backfilling...")
                scope.launch {
                    try {
                        resolveUrl(provider, fileId)
                        Log.d(TAG, "resolve: quality options backfilled for $fileId")
                    } catch (e: Exception) {
                        Log.w(TAG, "resolve: backfill quality options failed for $fileId: ${e.message}")
                    }
                }
            }
            return Uri.parse(cachedUrl)
        }

        val resolvedUrl = resolveUrl(provider, fileId) ?: return null
        CloudPlaylistCache.putResolvedUrl(provider, fileId, resolvedUrl)
        return Uri.parse(resolvedUrl)
    }

    /**
     * 刷新 123 云盘画质 URL（鉴权 token 可能过期，切换画质前调用）
     * 返回新的 QualityOption 列表，同时更新 VideoQualityCache
     */
    suspend fun refreshPan123QualityUrls(fileId: String): List<VideoQualityCache.QualityOption>? {
        val prefs = context.getSharedPreferences("pan123", Context.MODE_PRIVATE)
        val token = prefs.getString("token", "") ?: ""
        if (token.isBlank()) {
            Log.w(TAG, "refreshPan123QualityUrls: token is blank")
            return null
        }

        val metadata = CloudPlaylistCache.getFileMetadata("pan123", fileId)
        if (metadata == null) {
            Log.w(TAG, "refreshPan123QualityUrls: fileMetadata is null for $fileId")
            return null
        }

        val client = Pan123ApiClient()
        client.setToken("Bearer $token")
        val cfg = client.loadConfig().getOrNull()
        if (cfg == null) {
            Log.w(TAG, "refreshPan123QualityUrls: loadConfig failed")
            return null
        }

        val item = com.fluxplayer.app.core.data.pan123.Pan123FileItem(
            fileId = fileId,
            fileName = metadata.fileName,
            type = 0,
            size = metadata.size ?: return null,
            etag = metadata.etag ?: return null,
            s3keyFlag = metadata.s3keyFlag ?: "",
            downloadUrl = metadata.downloadUrl ?: "",
            createAt = "",
            trashedAt = "",
            starredStatus = 0
        )

        val playResult = client.getVideoPlayInfo(item).getOrNull()
        if (playResult == null || playResult.urls.isEmpty()) {
            Log.w(TAG, "refreshPan123QualityUrls: getVideoPlayInfo failed or empty")
            return null
        }

        val options = playResult.urls.zip(playResult.names).map { (u, n) ->
            VideoQualityCache.QualityOption(label = n, url = u)
        }
        videoQualityCache.cacheQualityOptions("pan123", fileId, fileId, options)
        Log.d(TAG, "refreshPan123QualityUrls: refreshed ${options.size} quality options")
        return options
    }

    suspend fun resolveUrl(provider: String, fileId: String): String? {
        return try {
            when (provider) {
                "alipan" -> resolveAlipan(fileId)
                "pan123" -> resolvePan123(fileId)
                "quark" -> resolveQuark(fileId)
                "uc" -> resolveUC(fileId)
                "cloud189" -> resolveCloud189(fileId)
                "yun139" -> resolveYun139(fileId)
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "resolveUrl failed: provider=$provider fileId=$fileId error=${e.message}", e)
            null
        }
    }

    private suspend fun resolvePan123(fileId: String): String? {
        Log.d(TAG, "========== resolvePan123 START: fileId=$fileId ==========")
        
        val prefs = context.getSharedPreferences("pan123", Context.MODE_PRIVATE)
        val token = prefs.getString("token", "") ?: ""
        if (token.isBlank()) {
            Log.e(TAG, "resolvePan123: token is BLANK! Cannot proceed.")
            return null
        }
        Log.d(TAG, "resolvePan123: token exists=${token.isNotBlank()}")

        val client = Pan123ApiClient()
        client.setToken("Bearer $token")

        // 注册 pan123 播放认证，播放器数据源自动注入
        clearOtherProviders("pan123")
        Pan123AuthProvider.authorization = "Bearer $token"
        Pan123AuthProvider.isActive = true
        
        // 注册所有 123 云盘 CDN 域名后缀（关键修复！）
        // 123云盘的CDN域名是 *.123295.com，不是 *.123pan.cn
        CloudPlayHeaders.registerSuffix(".123295.com") { Pan123AuthProvider.getPlayHeaders() }
        CloudPlayHeaders.registerSuffix(".123pan.cn") { Pan123AuthProvider.getPlayHeaders() }
        Log.d(TAG, "resolvePan123: Pan123AuthProvider activated, registered CDN domains")

        // Try cached metadata first
        val fileMetadata = CloudPlaylistCache.getFileMetadata("pan123", fileId)
        if (fileMetadata != null) {
            Log.d(TAG, "resolvePan123: fileMetadata found - fileName=${fileMetadata.fileName}, etag=${fileMetadata.etag}, size=${fileMetadata.size}")
            val etag = fileMetadata.etag ?: run {
                Log.e(TAG, "resolvePan123: etag is NULL!")
                return null
            }
            val size = fileMetadata.size ?: run {
                Log.e(TAG, "resolvePan123: size is NULL!")
                return null
            }
            val item = com.fluxplayer.app.core.data.pan123.Pan123FileItem(
                fileId = fileId,
                fileName = fileMetadata.fileName,
                type = 0,
                size = size,
                etag = etag,
                s3keyFlag = fileMetadata.s3keyFlag ?: "",
                downloadUrl = fileMetadata.downloadUrl ?: "",
                createAt = "",
                trashedAt = "",
                starredStatus = 0
            )
            
            // Step 1 & Step 2 并行：resolveDownloadUrlViaHead + getVideoPlayInfo
            // 两个网络请求同时发出，总耗时 = max(HEAD, getVideoPlayInfo)
            Log.d(TAG, "resolvePan123: launching parallel HEAD + getVideoPlayInfo...")

            val result = coroutineScope {
                val headUrlDeferred = async { client.resolveDownloadUrlViaHead(item) }
                val playResultDeferred = async { client.getVideoPlayInfo(item) }

                // 先处理 HEAD 结果（副作用：注册 CDN headers，解析 Referer）
                val headUrlResult = headUrlDeferred.await()
                var headUrl: String? = null
                if (headUrlResult.isSuccess) {
                    headUrl = headUrlResult.getOrThrow()
                    Log.d(TAG, "resolvePan123: resolveDownloadUrlViaHead SUCCESS url=${headUrl.take(150)}")
                    val dlHeaders = client.buildDownloadHeaders(headUrl)
                    Pan123AuthProvider.referer = dlHeaders["Referer"] ?: Pan123AuthProvider.referer
                    val host = try { java.net.URI(headUrl).host ?: "" } catch (_: Exception) { "" }
                    if (host.isNotBlank()) {
                        val suffix = host.substringAfter('.')
                        if (suffix.isNotBlank()) CloudPlayHeaders.registerSuffix(".$suffix", dlHeaders)
                    }
                } else {
                    Log.w(TAG, "resolvePan123: resolveDownloadUrlViaHead FAILED: ${headUrlResult.exceptionOrNull()?.message}")
                }

                // 再处理 getVideoPlayInfo 结果（优先播放路径）
                Log.d(TAG, "resolvePan123: processing getVideoPlayInfo result...")
                val playResult = playResultDeferred.await().getOrNull()
                if (playResult != null && playResult.urls.isNotEmpty()) {
                    Log.d(TAG, "resolvePan123: getVideoPlayInfo SUCCESS - urls size=${playResult.urls.size}")
                    playResult.urls.forEachIndexed { index, url ->
                        val isHls = url.contains(".m3u8") || url.contains("/hls/")
                        Log.d(TAG, "  [$index] isHls=$isHls url=${url.take(150)}")
                    }
                    if (playResult.urls.isNotEmpty()) {
                        val allUrls = playResult.urls.toMutableList()
                        val allNames = playResult.names.toMutableList()
                        val options = allUrls.zip(allNames).map { (u, n) ->
                            VideoQualityCache.QualityOption(label = n, url = u)
                        }
                        videoQualityCache.cacheQualityOptions("pan123", fileId, fileId, options)
                    }
                    val mp4Url = playResult.urls.find { !it.contains(".m3u8") && !it.contains("/hls/") }
                    val hlsUrl = playResult.urls.find { it.contains(".m3u8") || it.contains("/hls/") }
                    if (mp4Url != null && hlsUrl != null) {
                        Pan123FallbackCache.put(fileId, hlsUrl)
                    }
                    val bestUrl = mp4Url ?: playResult.urls.first()
                    Log.d(TAG, "resolvePan123: selected URL isMp4=${bestUrl == mp4Url} isHead=${bestUrl == headUrl} url=${bestUrl.take(150)}")
                    Log.d(TAG, "========== resolvePan123 END ==========")
                    return@coroutineScope bestUrl
                }

                // getVideoPlayInfo 失败，getFileDownloadUrl 兜底
                Log.w(TAG, "resolvePan123: getVideoPlayInfo failed, trying getFileDownloadUrl...")
                val dlUrl = client.getFileDownloadUrl(item).getOrNull()
                if (dlUrl != null) {
                    Log.d(TAG, "resolvePan123: getFileDownloadUrl SUCCESS")
                    Log.d(TAG, "========== resolvePan123 END (download fallback) ==========")
                    return@coroutineScope dlUrl
                }
                Log.e(TAG, "resolvePan123: getFileDownloadUrl also FAILED!")
                return@coroutineScope null
            }

            if (result != null) {
                Log.d(TAG, "========== resolvePan123 END ==========")
                return result + "#pan123Play=true#"
            }
        } else {
            Log.e(TAG, "resolvePan123: fileMetadata is NULL for fileId=$fileId")
        }

        Log.e(TAG, "========== resolvePan123 FAILED: returning null ==========")
        return null
    }


    private suspend fun resolveQuark(fileId: String): String? {
        val prefs = context.getSharedPreferences("quark", Context.MODE_PRIVATE)
        val cookie = prefs.getString("cookie", "") ?: ""
        if (cookie.isBlank() || !cookie.contains("__uid=")) {
            Log.w(TAG, "resolveQuark: cookie invalid")
            return null
        }

        val client = QuarkApiClient()
        client.setDriveType("quark")
        client.setCookie(cookie)
        clearOtherProviders("quark")

        // 注册夸克播放头（后缀匹配覆盖所有 *.quark.cn 子域名）
        CloudPlayHeaders.registerSuffix(".quark.cn") { QuarkAuthProvider.getPlayHeaders() }

        val playResult = client.getVideoPlayInfo(fileId).getOrNull()
        if (playResult != null && playResult.urls.isNotEmpty()) {
            Log.d(TAG, "resolveQuark: playUrl=${playResult.urls.first()}, qualities=${playResult.urls.size}")
            // 缓存所有清晰度选项，供播放器切换
            if (playResult.urls.size > 1) {
                val options = playResult.urls.zip(playResult.names).map { (u, n) ->
                    VideoQualityCache.QualityOption(label = n, url = u)
                }
                videoQualityCache.cacheQualityOptions("quark", fileId, fileId, options)
            }
            return playResult.urls.first() + "#quarkPlay=true#"
        }

        Log.w(TAG, "resolveQuark: trying downloadUrl")
        val dlUrl = client.getDownloadUrl(fileId).getOrNull()
        if (dlUrl != null) return dlUrl + "#quarkPlay=true#"
        return null
    }

    private suspend fun resolveUC(fileId: String): String? {
        val prefs = context.getSharedPreferences("uc", Context.MODE_PRIVATE)
        val cookie = prefs.getString("cookie", "") ?: ""
        if (cookie.isBlank() || !cookie.contains("__uid=")) return null

        val client = QuarkApiClient()
        client.setDriveType("uc")
        client.setCookie(cookie)
        clearOtherProviders("quark")

        // 注册 UC 播放头（后缀匹配覆盖 *.quark.cn 和 *.uc.cn 子域名）
        CloudPlayHeaders.registerSuffix(".quark.cn") { QuarkAuthProvider.getPlayHeaders() }
        CloudPlayHeaders.registerSuffix(".uc.cn") { QuarkAuthProvider.getPlayHeaders() }

        val playResult = client.getVideoPlayInfo(fileId).getOrNull()
        if (playResult != null && playResult.urls.isNotEmpty()) {
            // 缓存所有清晰度选项，供播放器切换
            if (playResult.urls.size > 1) {
                val options = playResult.urls.zip(playResult.names).map { (u, n) ->
                    VideoQualityCache.QualityOption(label = n, url = u)
                }
                videoQualityCache.cacheQualityOptions("uc", fileId, fileId, options)
            }
            return playResult.urls.first() + "#ucPlay=true#"
        }
        val dlUrl = client.getDownloadUrl(fileId).getOrNull()
        if (dlUrl != null) return dlUrl + "#ucPlay=true#"
        return null
    }

    private suspend fun resolveCloud189(fileId: String): String? {
        if (!C189AuthProvider.isActive) {
            Log.w(TAG, "resolveCloud189: C189AuthProvider is not active")
            return null
        }

        clearOtherProviders("cloud189")
        // 注册天翼云盘播放头（后缀匹配覆盖所有 *.189.cn 子域名）
        CloudPlayHeaders.registerSuffix(".189.cn") { C189AuthProvider.getPlayHeaders() }

        val client = C189ApiClient().apply {
            accessToken = C189AuthProvider.accessToken
            sessionKey = C189AuthProvider.sessionKey
            sessionSecret = C189AuthProvider.sessionSecret
        }
        val url = client.getVideoPlayUrl(fileId).getOrNull()
        Log.d(TAG, "resolveCloud189 fileId=$fileId url=$url")
        if (url != null) return url + "#189Play=true#"
        return null
    }

    private suspend fun resolveYun139(fileId: String): String? {
        if (!Yun139AuthProvider.isActive) return null

        clearOtherProviders("yun139")
        // 注册移动云盘播放头（后缀匹配覆盖所有 *.139.com 子域名）
        CloudPlayHeaders.registerSuffix(".139.com") { Yun139AuthProvider.getPlayHeaders() }

        val fileMetadata = CloudPlaylistCache.getFileMetadata("yun139", fileId)
        val client = Yun139ApiClient()

        val playUrl = client.getVideoPreviewUrl(fileId).getOrNull()
        if (playUrl != null) return playUrl + "#yun139Play=true#"

        if (fileMetadata != null) {
            val dlUrl = client.getDownloadUrl(fileId, fileMetadata.fileName).getOrNull()
            if (dlUrl != null) return dlUrl + "#yun139Play=true#"
        }
        return null
    }

    private suspend fun resolveAlipan(fileId: String): String? {
        if (!AliyunAuthProvider.isActive) return null

        val prefs = context.getSharedPreferences("alipan", Context.MODE_PRIVATE)
        val auth = prefs.getString("authorization", "") ?: ""
        if (auth.isBlank()) return null

        val client = AliyunApiClient()
        client.authorization = auth
        clearOtherProviders("alipan")
        AliyunAuthProvider.authorization = auth
        AliyunAuthProvider.isActive = true

        val driveId = prefs.getString("drive_id", "") ?: ""
        if (driveId.isNotBlank()) client.driveId = driveId

        val deviceId = prefs.getString("device_id", "")
        if (!deviceId.isNullOrBlank()) client.setDeviceId(deviceId)

        val signature = prefs.getString("signature", "")
        if (!signature.isNullOrBlank()) client.setSignature(signature)

        // 注册阿里云播放头（后缀匹配覆盖所有 *.alipan.com 子域名）
        CloudPlayHeaders.registerSuffix(".alipan.com") { AliyunAuthProvider.getPlayHeaders() }

        val playResult = client.getVideoPreviewPlayInfo(fileId).getOrNull()
        if (playResult != null && playResult.urls.isNotEmpty()) {
            // 缓存所有清晰度选项，供播放器切换
            if (playResult.urls.size > 1) {
                val options = playResult.urls.zip(playResult.names).map { (u, n) ->
                    VideoQualityCache.QualityOption(label = n, url = u)
                }
                videoQualityCache.cacheQualityOptions("alipan", fileId, fileId, options)
            }
            return playResult.urls.first() + "#alipanPlay=true#"
        }

        val dlUrl = client.getDownloadUrl(fileId).getOrNull()
        if (dlUrl != null) return dlUrl + "#alipanPlay=true#"
        return null
    }
}
