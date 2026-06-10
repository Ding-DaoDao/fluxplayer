package dev.anilbeesetti.nextplayer.core.data.cloud

import android.content.Context
import android.net.Uri
import android.util.Log
import dev.anilbeesetti.nextplayer.core.common.CloudPlaylistCache
import dev.anilbeesetti.nextplayer.core.common.CloudUriScheme
import dev.anilbeesetti.nextplayer.core.data.aliyun.AliyunApiClient
import dev.anilbeesetti.nextplayer.core.data.aliyun.AliyunAuthProvider
import dev.anilbeesetti.nextplayer.core.data.cloud189.C189ApiClient
import dev.anilbeesetti.nextplayer.core.data.cloud189.C189AuthProvider
import dev.anilbeesetti.nextplayer.core.data.pan123.Pan123ApiClient
import dev.anilbeesetti.nextplayer.core.data.quark.QuarkApiClient
import dev.anilbeesetti.nextplayer.core.data.quark.QuarkAuthProvider
import dev.anilbeesetti.nextplayer.core.data.yun139.Yun139ApiClient
import dev.anilbeesetti.nextplayer.core.data.yun139.Yun139AuthProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudUriResolver @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "CloudUriResolver"
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

        // Try cached metadata first
        val fileMetadata = CloudPlaylistCache.getFileMetadata("pan123", fileId)
        if (fileMetadata != null) {
            val etag = fileMetadata.etag ?: return null
            val size = fileMetadata.size ?: return null
            val item = dev.anilbeesetti.nextplayer.core.data.pan123.Pan123FileItem(
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
                return playResult.urls.first() + "#isVideo=true#"
            }
            // Fallback to download
            val dlUrl = client.getFileDownloadUrl(item).getOrNull()
            if (dlUrl != null) return dlUrl + "#isVideo=true#"
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
        QuarkAuthProvider.cookie = cookie
        QuarkAuthProvider.isActive = true

        val playResult = client.getVideoPlayInfo(fileId).getOrNull()
        if (playResult != null && playResult.urls.isNotEmpty()) {
            Log.d(TAG, "resolveQuark: playUrl=${playResult.urls.first()}")
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
        QuarkAuthProvider.cookie = cookie
        QuarkAuthProvider.isActive = true

        val playResult = client.getVideoPlayInfo(fileId).getOrNull()
        if (playResult != null && playResult.urls.isNotEmpty()) {
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
        AliyunAuthProvider.authorization = auth
        AliyunAuthProvider.isActive = true

        val driveId = prefs.getString("drive_id", "") ?: ""
        if (driveId.isNotBlank()) client.driveId = driveId

        val deviceId = prefs.getString("device_id", "")
        if (!deviceId.isNullOrBlank()) client.setDeviceId(deviceId)

        val signature = prefs.getString("signature", "")
        if (!signature.isNullOrBlank()) client.setSignature(signature)

        val playResult = client.getVideoPreviewPlayInfo(fileId).getOrNull()
        if (playResult != null && playResult.urls.isNotEmpty()) {
            return playResult.urls.first() + "#alipanPlay=true#"
        }

        val dlUrl = client.getDownloadUrl(fileId).getOrNull()
        if (dlUrl != null) return dlUrl + "#alipanPlay=true#"
        return null
    }
}
