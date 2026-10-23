package com.fluxplayer.app.core.data.cloud

import android.content.Context
import android.net.Uri
import android.util.Log
import com.fluxplayer.app.core.common.CloudPlayHeaders
import com.fluxplayer.app.core.common.CloudPlaylistCache
import com.fluxplayer.app.core.common.CloudUriScheme
import com.fluxplayer.app.core.common.Pan123FallbackCache
import com.fluxplayer.app.core.common.VideoQualityCache
import com.fluxplayer.app.core.common.sanitizeUrl
import com.fluxplayer.app.core.data.GlobalCookieJar
import com.fluxplayer.app.core.data.aliyun.AliyunApiClient
import com.fluxplayer.app.core.data.aliyun.AliyunAuthProvider
import com.fluxplayer.app.core.data.cloud189.C189ApiClient
import com.fluxplayer.app.core.data.cloud189.C189AuthProvider
import com.fluxplayer.app.core.data.pan123.Pan123ApiClient
import com.fluxplayer.app.core.data.pan123.Pan123AuthProvider
import com.fluxplayer.app.core.data.quark.QuarkApiClient
import com.fluxplayer.app.core.data.quark.QuarkAuthProvider
import com.fluxplayer.app.core.data.security.getSecret
import com.fluxplayer.app.core.data.security.putSecret
import com.fluxplayer.app.core.data.yun139.Yun139ApiClient
import com.fluxplayer.app.core.data.yun139.Yun139AuthProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@Singleton
class CloudUriResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val videoQualityCache: VideoQualityCache,
) {
    companion object {
        private const val TAG = "CloudUriResolver"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 播放上下文独立选择，不清除其他网盘仍在使用的登录凭据。 */
    private fun selectPlaybackProvider(provider: String) {
        CloudPlayHeaders.activeProvider = provider
    }

    suspend fun resolve(uri: Uri): Uri? {
        if (!CloudUriScheme.isCloudUri(uri)) return null
        val provider = CloudUriScheme.getProvider(uri) ?: return null
        val fileId = CloudUriScheme.getFileId(uri) ?: return null
        selectPlaybackProvider(provider)

        if (uri.getQueryParameter("space") != null || uri.getQueryParameter("extra_parent") != null) {
            val features = extraFeatures(provider) ?: return null
            val spaceId = uri.getQueryParameter("space")
            val url = if (spaceId != null) {
                features.resolveSpaceFile(
                    CloudSpace(spaceId, uri.getQueryParameter("space_name").orEmpty()),
                    CloudRemoteFile(
                        fileId,
                        uri.getQueryParameter("file_name").orEmpty(),
                        false,
                        token = uri.getQueryParameter("space_path").orEmpty(),
                        parentId = uri.getQueryParameter("space_parent").orEmpty(),
                    ),
                )
            } else {
                features.resolvePersonalFile(fileId, uri.getQueryParameter("extra_parent").orEmpty())
            }
            val host = Uri.parse(url).host.orEmpty()
            if (!CloudPlayHeaders.isExplicitlyAnonymous(host)) CloudPlayHeaders.register(host, features.fileHeaders())
            return Uri.parse(url)
        }

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

    private fun extraFeatures(provider: String): CloudProviderFeatures? {
        val prefs = context.getSharedPreferences(provider, Context.MODE_PRIVATE)
        return when (provider) {
            "alipan" -> {
                val auth = prefs.getSecret(provider, "authorization").orEmpty()
                if (auth.isBlank()) return null
                CloudProviderFeatures.from(
                    AliyunApiClient().apply {
                        configureSession(context)
                        authorization = auth
                        driveId = prefs.getString("drive_id", "").orEmpty()
                        prefs.getSecret(provider, "device_id")?.let { setDeviceId(it) }
                        prefs.getSecret(provider, "signature")?.let { setSignature(it) }
                    },
                )
            }
            "pan123" -> {
                val token = prefs.getSecret(provider, "token").orEmpty()
                if (token.isBlank()) return null
                CloudProviderFeatures.from(
                    Pan123ApiClient().apply {
                        configureSession(context)
                        setToken("Bearer ${token.removePrefix("Bearer ")}")
                    },
                )
            }
            "quark", "uc" -> {
                val cookie = prefs.getSecret(provider, "cookie").orEmpty()
                if (cookie.isBlank()) return null
                CloudProviderFeatures.from(
                    QuarkApiClient().apply {
                        configureSession(context)
                        setDriveType(provider)
                        setCookie(cookie)
                        onCookieUpdated = { merged -> prefs.edit().putSecret(provider, "cookie", merged).apply() }
                    },
                    provider,
                )
            }
            "yun139" -> {
                val token = prefs.getSecret(provider, "authorization").orEmpty()
                if (token.isBlank()) return null
                CloudProviderFeatures.from(
                    Yun139ApiClient().apply {
                        setToken(token, prefs.getSecret(provider, "phoneNumber").orEmpty(), prefs.getSecret(provider, "userDomainId").orEmpty())
                        Yun139AuthProvider.cookie = prefs.getSecret(provider, "cookies").orEmpty()
                    },
                )
            }
            "cloud189" -> {
                C189AuthProvider.restoreSavedSession()
                if (!C189AuthProvider.hasValidSession()) return null
                CloudProviderFeatures.from(C189ApiClient())
            }
            else -> null
        }
    }

    /**
     * 刷新 123 云盘画质 URL（鉴权 token 可能过期，切换画质前调用）
     * 返回新的 QualityOption 列表，同时更新 VideoQualityCache
     */
    suspend fun refreshPan123QualityUrls(fileId: String): List<VideoQualityCache.QualityOption>? {
        val prefs = context.getSharedPreferences("pan123", Context.MODE_PRIVATE)
        val token = prefs.getSecret("pan123", "token").orEmpty()
        if (token.isBlank()) {
            Log.w(TAG, "refreshPan123QualityUrls: token is blank")
            return null
        }

        val metadata = CloudPlaylistCache.getFileMetadata("pan123", fileId)
        if (metadata == null) {
            Log.w(TAG, "refreshPan123QualityUrls: fileMetadata is null for $fileId")
            return null
        }

        val client = Pan123ApiClient().apply { configureSession(context) }
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
            starredStatus = 0,
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
        val token = prefs.getSecret("pan123", "token").orEmpty()
        if (token.isBlank()) {
            Log.e(TAG, "resolvePan123: token is BLANK! Cannot proceed.")
            return null
        }
        Log.d(TAG, "resolvePan123: token exists=${token.isNotBlank()}")

        val client = Pan123ApiClient().apply { configureSession(context) }
        client.setToken("Bearer $token")

        // 注册 pan123 播放认证，播放器数据源自动注入
        selectPlaybackProvider("pan123")
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
                starredStatus = 0,
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
                    Log.d(TAG, "resolvePan123: resolveDownloadUrlViaHead SUCCESS url=${sanitizeUrl(headUrl)}")
                    val dlHeaders = client.buildDownloadHeaders(headUrl)
                    Pan123AuthProvider.referer = dlHeaders["Referer"] ?: Pan123AuthProvider.referer
                    val host = try {
                        java.net.URI(headUrl).host ?: ""
                    } catch (_: Exception) {
                        ""
                    }
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
                        Log.d(TAG, "  [$index] isHls=$isHls url=${sanitizeUrl(url)}")
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
                    Log.d(TAG, "resolvePan123: selected URL isMp4=${bestUrl == mp4Url} isHead=${bestUrl == headUrl} url=${sanitizeUrl(bestUrl)}")
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
        val cookie = prefs.getSecret("quark", "cookie").orEmpty()
        if (cookie.isBlank() || !cookie.contains("__uid=")) {
            Log.w(TAG, "resolveQuark: cookie invalid")
            return null
        }

        val client = QuarkApiClient()
        client.setDriveType("quark")
        client.setCookie(cookie)
        // API 响应中的 Set-Cookie 实时写回 SharedPreferences，防止后续操作使用过期的旧 cookie
        client.onCookieUpdated = { merged -> prefs.edit().putSecret("quark", "cookie", merged).apply() }
        selectPlaybackProvider("quark")

        // 注册夸克播放头（后缀匹配覆盖所有 *.quark.cn 子域名）
        CloudPlayHeaders.registerSuffix(".quark.cn") { QuarkAuthProvider.getPlayHeaders() }

        if (prefs.getBoolean("prefer_original_playback", false)) {
            val original = client.getOriginalPlaybackUrl(fileId).getOrNull()
            if (!original.isNullOrBlank()) return original + "#quarkPlay=true#"
        }

        val playResult = client.getVideoPlayInfo(fileId).getOrNull()
        if (playResult != null && playResult.urls.isNotEmpty()) {
            Log.d(TAG, "resolveQuark: playUrl=${sanitizeUrl(playResult.urls.first())}, qualities=${playResult.urls.size}")
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
        val cookie = prefs.getSecret("uc", "cookie").orEmpty()
        if (cookie.isBlank() || !cookie.contains("__uid=")) return null

        val tvService = com.fluxplayer.app.core.data.quark.UcTvService(context)
        if (tvService.isAuthorized()) {
            val qualities = tvService.playback(fileId)
            qualities.forEach { (url, _) -> Uri.parse(url).host?.let { host -> CloudPlayHeaders.register(host) { emptyMap() } } }
            val options = qualities.map { (url, name) -> VideoQualityCache.QualityOption(label = name, url = url) }
            videoQualityCache.cacheQualityOptions("uc", fileId, fileId, options)
            return qualities.first().first
        }

        val client = QuarkApiClient()
        client.setDriveType("uc")
        client.setCookie(cookie)
        // API 响应中的 Set-Cookie 实时写回 SharedPreferences，防止后续操作使用过期的旧 cookie
        client.onCookieUpdated = { merged -> prefs.edit().putSecret("uc", "cookie", merged).apply() }
        selectPlaybackProvider("uc")

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
        C189AuthProvider.restoreSavedSession()
        if (!C189AuthProvider.isActive) {
            Log.w(TAG, "resolveCloud189: C189AuthProvider is not active")
            return null
        }

        selectPlaybackProvider("cloud189")
        // 注册天翼云盘播放头（后缀匹配覆盖所有 *.189.cn 子域名）
        CloudPlayHeaders.registerSuffix(".189.cn") { C189AuthProvider.getPlayHeaders() }

        val client = C189ApiClient()
        val url = client.getVideoPlayUrl(fileId).getOrNull()
        Log.d(TAG, "resolveCloud189 fileId=$fileId url=${url?.let(::sanitizeUrl)}")
        if (url != null) return url + "#189Play=true#"
        return null
    }

    private suspend fun resolveYun139(fileId: String): String? {
        if (!Yun139AuthProvider.isActive) return null

        selectPlaybackProvider("yun139")
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
        val auth = prefs.getSecret("alipan", "authorization").orEmpty()
        if (auth.isBlank()) return null

        val client = AliyunApiClient().apply { configureSession(context) }
        client.authorization = auth
        selectPlaybackProvider("alipan")
        AliyunAuthProvider.authorization = auth
        AliyunAuthProvider.isActive = true

        val driveId = prefs.getString("drive_id", "") ?: ""
        if (driveId.isNotBlank()) client.driveId = driveId

        val deviceId = prefs.getSecret("alipan", "device_id")
        if (!deviceId.isNullOrBlank()) client.setDeviceId(deviceId)

        val signature = prefs.getSecret("alipan", "signature")
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
