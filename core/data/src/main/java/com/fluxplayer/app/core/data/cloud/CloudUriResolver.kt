package com.fluxplayer.app.core.data.cloud

import android.content.Context
import android.net.Uri
import android.util.Log
import com.fluxplayer.app.core.common.CloudPlayHeaders
import com.fluxplayer.app.core.common.CloudPlaylistCache
import com.fluxplayer.app.core.common.CloudUriScheme
import com.fluxplayer.app.core.common.VideoQualityCache
import com.fluxplayer.app.core.data.aliyun.AliyunApiClient
import com.fluxplayer.app.core.data.aliyun.AliyunAuthProvider
import com.fluxplayer.app.core.data.cloud189.C189ApiClient
import com.fluxplayer.app.core.data.cloud189.C189AuthProvider
import com.fluxplayer.app.core.data.pan123.Pan123ApiClient
import com.fluxplayer.app.core.data.pan123.Pan123AuthProvider
import com.fluxplayer.app.core.data.quark.QuarkApiClient
import com.fluxplayer.app.core.data.quark.QuarkAuthProvider
import com.fluxplayer.app.core.data.yun139.Yun139ApiClient
import com.fluxplayer.app.core.data.yun139.Yun139AuthProvider
import dagger.hilt.android.qualifiers.ApplicationContext
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

    /** 清除除指定 Provider 之外的所有云盘认证状态，防止 HLS 分片兜底注入时 Cookie/Token 串号 */
    private fun clearOtherProviders(except: String) {
        if (except != "quark") QuarkAuthProvider.clear()
        if (except != "alipan") AliyunAuthProvider.clear()
        if (except != "pan123") Pan123AuthProvider.clear()
        if (except != "cloud189") C189AuthProvider.clear()
        if (except != "yun139") Yun139AuthProvider.clear()
    }

    suspend fun resolve(uri: Uri): Uri? {
        if (!CloudUriScheme.isCloudUri(uri)) return null
        val provider = CloudUriScheme.getProvider(uri) ?: return null
        val fileId = CloudUriScheme.getFileId(uri) ?: return null

        // Check cache first
        CloudPlaylistCache.getResolvedUrl(provider, fileId)?.let { return Uri.parse(it) }

        val resolvedUrl = resolveUrl(provider, fileId) ?: return null
        CloudPlaylistCache.putResolvedUrl(provider, fileId, resolvedUrl)
        return Uri.parse(resolvedUrl)
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
        val prefs = context.getSharedPreferences("pan123", Context.MODE_PRIVATE)
        val token = prefs.getString("token", "") ?: ""
        if (token.isBlank()) return null

        val client = Pan123ApiClient()
        client.setToken("Bearer $token")

        // 注册 pan123 播放认证，播放器数据源自动注入
        clearOtherProviders("pan123")
        Pan123AuthProvider.authorization = "Bearer $token"
        Pan123AuthProvider.isActive = true
        CloudPlayHeaders.registerSuffix(".123pan.cn") { Pan123AuthProvider.getPlayHeaders() }

        // Try cached metadata first
        val fileMetadata = CloudPlaylistCache.getFileMetadata("pan123", fileId)
        if (fileMetadata != null) {
            val etag = fileMetadata.etag ?: return null
            val size = fileMetadata.size ?: return null
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
            // Try video play first
            val playResult = client.getVideoPlayInfo(item).getOrNull()
            if (playResult != null && playResult.urls.isNotEmpty()) {
                // 缓存所有清晰度选项，供播放器切换
                if (playResult.urls.size > 1) {
                    val options = playResult.urls.zip(playResult.names).map { (u, n) ->
                        VideoQualityCache.QualityOption(label = n, url = u)
                    }
                    videoQualityCache.cacheQualityOptions("pan123", fileId, fileId, options)
                }
                return playResult.urls.first() + "#pan123Play=true#"
            }
            // Fallback to download
            val dlUrl = client.getFileDownloadUrl(item).getOrNull()
            if (dlUrl != null) return dlUrl + "#pan123Play=true#"
        }
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
