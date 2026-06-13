package com.fluxplayer.app.core.data.webdav

import android.net.Uri
import android.util.Log
import com.fluxplayer.app.core.model.WebDavResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.concurrent.TimeUnit

/**
 * WebDAV 客户端 —— 使用 PROPFIND 协议列出目录。
 *
 * 不依赖任何外部 XML 库，使用 Android 内置 XmlPullParser。
 */
class WebDavClient(
    private val client: OkHttpClient = DEFAULT_CLIENT,
) {

    companion object {
        private const val TAG = "WebDavClient"

        private val DEFAULT_CLIENT = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        private val MEDIA_TYPE_XML = "application/xml; charset=utf-8".toMediaType()

        private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<D:propfind xmlns:D="DAV:">
    <D:prop>
        <D:displayname/>
        <D:resourcetype/>
        <D:getcontentlength/>
        <D:getlastmodified/>
    </D:prop>
</D:propfind>"""
    }

    /**
     * 列出指定路径下的文件和目录。
     *
     * @param baseUrl WebDAV 服务器基础 URL（如 http://192.168.1.100:5005）
     * @param path 要列出的路径（如 / 或 /视频/电影）
     * @param authHeader Authorization header 值（如 "Basic xxx"）
     * @return Result 包含资源列表，第一项通常是请求的目录自身
     */
    suspend fun listDirectory(
        baseUrl: String,
        path: String,
        authHeader: String,
    ): Result<List<WebDavResource>> = withContext(Dispatchers.IO) {
        try {
            val normalizedBase = baseUrl.trimEnd('/')
            val url = if (path.startsWith("/")) "$normalizedBase$path" else "$normalizedBase/$path"
            val targetUrl = if (url.endsWith("/")) url else "$url/"

            val request = Request.Builder()
                .url(targetUrl)
                .header("Authorization", authHeader)
                .header("Depth", "1")
                .method("PROPFIND", PROPFIND_BODY.toRequestBody(MEDIA_TYPE_XML))
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.w(TAG, "PROPFIND failed: HTTP ${response.code} for $url")
                return@withContext Result.failure(
                    WebDavException("HTTP ${response.code}: ${response.message}")
                )
            }

            val body = response.body?.string() ?: return@withContext Result.success(emptyList())
            val resources = parsePropfindResponse(body, normalizedBase, path)
            Result.success(resources)
        } catch (e: Exception) {
            Log.e(TAG, "listDirectory failed for path=$path", e)
            Result.failure(e)
        }
    }

    /**
     * 测试 WebDAV 服务器连接（使用 OPTIONS 请求）。
     */
    suspend fun testConnection(
        baseUrl: String,
        username: String,
        password: String,
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val normalizedBase = baseUrl.trimEnd('/')
            val auth = "Basic " + java.util.Base64.getEncoder().encodeToString(
                "$username:$password".toByteArray()
            )
            val request = Request.Builder()
                .url(normalizedBase)
                .header("Authorization", auth)
                .method("OPTIONS", null)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                Result.success(true)
            } else {
                Result.failure(WebDavException("HTTP ${response.code}: ${response.message}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "testConnection failed", e)
            Result.failure(e)
        }
    }

    /**
     * 解析 PROPFIND XML 响应。
     *
     * 标准格式：
     * ```xml
     * <D:multistatus xmlns:D="DAV:">
     *   <D:response>
     *     <D:href>/video.mp4</D:href>
     *     <D:propstat>
     *       <D:prop>
     *         <D:displayname>video.mp4</D:displayname>
     *         <D:resourcetype/>
     *         <D:getcontentlength>12345</D:getcontentlength>
     *         <D:getlastmodified>Mon, 01 Jan 2024 00:00:00 GMT</D:getlastmodified>
     *       </D:prop>
     *     </D:propstat>
     *   </D:response>
     * </D:multistatus>
     * ```
     */
    private fun parsePropfindResponse(
        xml: String,
        baseUrl: String,
        queryPath: String = "/",
    ): List<WebDavResource> {
        val results = mutableListOf<WebDavResource>()

        // 提取 baseUrl 的路径部分，用于 href 相对化处理
        // 例如 https://webdav.123pan.cn/webdav → /webdav
        val basePath = Uri.parse(baseUrl).path?.trimEnd('/') ?: ""
        // 当前查询路径（带 / 前缀），用于跳过 PROPFIND 返回的"当前目录自身"记录
        val normalizedQuery = queryPath.trimEnd('/').let { if (it.isEmpty()) "/" else it }

        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        var currentHref: String? = null
        var currentName: String? = null
        var currentIsDir = false
        var currentSize = 0L
        var currentLastModified = ""
        var currentCreatedAt = ""
        var inResponse = false
        var inProp = false
        var inPropstat = false

        @Suppress("SpellCheckingInspection")
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    val tagName = parser.name.lowercase()
                    when {
                        tagName.endsWith("response") -> {
                            inResponse = true
                            currentHref = null
                            currentName = null
                            currentIsDir = false
                            currentSize = 0L
                            currentLastModified = ""
                            currentCreatedAt = ""
                        }
                        tagName.endsWith("propstat") -> inPropstat = true
                        tagName.endsWith("prop") -> if (inPropstat) inProp = true
                        tagName.endsWith("href") -> {
                            currentHref = parser.nextText().trim()
                        }
                        tagName.endsWith("displayname") -> {
                            currentName = parser.nextText().trim()
                        }
                        tagName.endsWith("getcontentlength") -> {
                            val text = parser.nextText().trim()
                            currentSize = text.toLongOrNull() ?: 0L
                        }
                        tagName.endsWith("getlastmodified") -> {
                            currentLastModified = parser.nextText().trim()
                        }
                        tagName.endsWith("creationdate") -> {
                            currentCreatedAt = parser.nextText().trim()
                        }
                        tagName.endsWith("collection") -> {
                            currentIsDir = true
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    val tagName = parser.name.lowercase()
                    when {
                        tagName.endsWith("prop") -> inProp = false
                        tagName.endsWith("propstat") -> inPropstat = false
                        tagName.endsWith("response") -> {
                            inResponse = false
                            val href = currentHref
                            // 跳过 null / 根目录自身
                            if (!href.isNullOrEmpty()) {
                                // 先对 href 做 URL 解码（兼容非 ASCII 字符如中文、日文等）
                                val decodedHref = try {
                                    java.net.URLDecoder.decode(href, "UTF-8")
                                } catch (_: Exception) {
                                    href
                                }
                                // 将原始 href 转为相对于 baseUrl 的路径
                                // 兼容三种格式：
                                //   绝对 URL: https://host/webdav/动漫/ → /动漫/
                                //   域名绝对路径: /webdav/动漫/ → /动漫/
                                //   相对路径: 动漫/ → /动漫/
                                val relativePath = when {
                                    decodedHref.startsWith("http://") || decodedHref.startsWith("https://") -> {
                                        Uri.parse(decodedHref).path ?: decodedHref
                                    }
                                    basePath.isNotEmpty() && decodedHref.startsWith("$basePath/") -> {
                                        decodedHref.removePrefix(basePath)
                                    }
                                    basePath.isNotEmpty() && decodedHref == basePath -> {
                                        "/"
                                    }
                                    else -> decodedHref
                                }.trimEnd('/').let { if (it.isEmpty()) "/" else it }

                                // 跳过根目录自身 或 当前目录自身
                                if (relativePath != "/" && relativePath != normalizedQuery) {
                                    val name = currentName
                                        ?: relativePath.trimEnd('/').substringAfterLast('/')
                                        .ifEmpty { relativePath }

                                    results.add(
                                        WebDavResource(
                                            name = name,
                                            path = relativePath,
                                            isDirectory = currentIsDir,
                                            size = currentSize,
                                            lastModified = currentLastModified,
                                            createdAt = currentCreatedAt,
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
            parser.next()
        }

        return results
    }

    /**
     * 创建文件夹（MKCOL 方法）。
     */
    suspend fun createFolder(
        baseUrl: String,
        path: String,
        authHeader: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val normalizedBase = baseUrl.trimEnd('/')
            val url = if (path.startsWith("/")) "$normalizedBase$path" else "$normalizedBase/$path"
            val targetUrl = if (url.endsWith("/")) url else "$url/"

            val request = Request.Builder()
                .url(targetUrl)
                .header("Authorization", authHeader)
                .method("MKCOL", null)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful || response.code == 201) {
                Result.success(Unit)
            } else {
                Result.failure(WebDavException("HTTP ${response.code}: ${response.message}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "createFolder failed for path=$path", e)
            Result.failure(e)
        }
    }

    /**
     * 删除资源（DELETE 方法）。
     */
    suspend fun delete(
        baseUrl: String,
        path: String,
        authHeader: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val normalizedBase = baseUrl.trimEnd('/')
            val url = if (path.startsWith("/")) "$normalizedBase$path" else "$normalizedBase/$path"

            val request = Request.Builder()
                .url(url)
                .header("Authorization", authHeader)
                .delete()
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful || response.code == 204) {
                Result.success(Unit)
            } else {
                Result.failure(WebDavException("HTTP ${response.code}: ${response.message}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "delete failed for path=$path", e)
            Result.failure(e)
        }
    }

    /**
     * 移动资源（MOVE 方法）。
     */
    suspend fun move(
        baseUrl: String,
        sourcePath: String,
        destinationPath: String,
        authHeader: String,
        overwrite: Boolean = false,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val normalizedBase = baseUrl.trimEnd('/')
            val sourceUrl = if (sourcePath.startsWith("/")) "$normalizedBase$sourcePath" else "$normalizedBase/$sourcePath"
            val destUrl = buildEncodedDestUrl(normalizedBase, destinationPath)

            val request = Request.Builder()
                .url(sourceUrl)
                .header("Authorization", authHeader)
                .header("Destination", destUrl)
                .header("Overwrite", if (overwrite) "T" else "F")
                .method("MOVE", null)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful || response.code in 200..299) {
                Result.success(Unit)
            } else {
                Result.failure(WebDavException("HTTP ${response.code}: ${response.message}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "move failed from $sourcePath to $destinationPath", e)
            Result.failure(e)
        }
    }

    /**
     * 复制资源（COPY 方法）。
     */
    suspend fun copy(
        baseUrl: String,
        sourcePath: String,
        destinationPath: String,
        authHeader: String,
        overwrite: Boolean = false,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val normalizedBase = baseUrl.trimEnd('/')
            val sourceUrl = if (sourcePath.startsWith("/")) "$normalizedBase$sourcePath" else "$normalizedBase/$sourcePath"
            val destUrl = buildEncodedDestUrl(normalizedBase, destinationPath)

            val request = Request.Builder()
                .url(sourceUrl)
                .header("Authorization", authHeader)
                .header("Destination", destUrl)
                .header("Overwrite", if (overwrite) "T" else "F")
                .method("COPY", null)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful || response.code in 200..299) {
                Result.success(Unit)
            } else {
                Result.failure(WebDavException("HTTP ${response.code}: ${response.message}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "copy failed from $sourcePath to $destinationPath", e)
            Result.failure(e)
        }
    }

    /**
     * 构建 URL 编码的 Destination 头。
     * 对路径中的每一段单独编码，避免中文等非 ASCII 字符导致 HTTP 解析失败。
     */
    private fun buildEncodedDestUrl(baseUrl: String, path: String): String {
        val normalizedBase = baseUrl.trimEnd('/')
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        val encodedPath = normalizedPath.split("/").joinToString("/") { segment ->
            if (segment.isEmpty()) "" else Uri.encode(segment)
        }
        return "$normalizedBase$encodedPath"
    }

    /**
     * 上传文件到 WebDAV 服务器（PUT 方法）。
     */
    suspend fun put(
        baseUrl: String,
        path: String,
        data: ByteArray,
        authHeader: String,
        contentType: String = "application/octet-stream",
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val normalizedBase = baseUrl.trimEnd('/')
            val url = if (path.startsWith("/")) "$normalizedBase$path" else "$normalizedBase/$path"

            val request = Request.Builder()
                .url(url)
                .header("Authorization", authHeader)
                .put(data.toRequestBody(contentType.toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            response.use { resp ->
                if (resp.isSuccessful || resp.code in 200..299) {
                    // 消费 response body 确保连接完全 flush（某些 WebDAV 实现需要）
                    resp.body?.bytes()
                    Log.d(TAG, "PUT success for path=$path")
                    Result.success(Unit)
                } else {
                    val errBody = resp.body?.string() ?: ""
                    Log.w(TAG, "PUT failed for path=$path: HTTP ${resp.code} body=$errBody")
                    Result.failure(WebDavException("HTTP ${resp.code}: ${resp.message}, body=$errBody"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "put failed for path=$path", e)
            Result.failure(e)
        }
    }

    /**
     * 从 WebDAV 服务器下载文件（GET 方法）。
     */
    suspend fun get(
        baseUrl: String,
        path: String,
        authHeader: String,
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val normalizedBase = baseUrl.trimEnd('/')
            val url = if (path.startsWith("/")) "$normalizedBase$path" else "$normalizedBase/$path"

            val request = Request.Builder()
                .url(url)
                .header("Authorization", authHeader)
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                Result.success(response.body?.bytes() ?: ByteArray(0))
            } else {
                Result.failure(WebDavException("HTTP ${response.code}: ${response.message}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "get failed for path=$path", e)
            Result.failure(e)
        }
    }

    class WebDavException(message: String) : Exception(message)
}
