package com.fluxplayer.app.core.tingshu

import android.content.Context
import android.net.Uri
import android.util.Log
import com.github.eprendre.tingshu.utils.Book
import com.github.eprendre.tingshu.utils.Episode
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import voice.core.extension.engine.ExtensionSourceMeta
import voice.core.extension.engine.JdrArchive
import voice.core.extension.engine.JsSourceEngine
import voice.core.extension.engine.OkHttpSandboxHttp
import voice.core.extension.engine.Pan123ScriptCompatibility
import voice.core.extension.engine.SourceContract
import voice.core.extension.engine.SourceFeatures
import voice.core.extension.engine.SourceLogin
import voice.core.extension.engine.SourceSetting

/** 每个脚本使用自己的 QuickJS 线程，宿主调用由仓库调度器串行处理。 */
data class JdrConfiguration(val fields: List<SourceSetting>, val values: Map<String, String>, val canLogin: Boolean, val canBrowse: Boolean, val initialDirectory: String)
data class JdrFolder(val id: String, val name: String)
data class JdrBrowserPage(val directoryId: String, val folders: List<JdrFolder>, val books: List<Book>, val nextPage: Int?)

internal class JdrSourceBackend(private val context: Context) {
    private data class Source(val archive: JdrArchive, val metadata: ExtensionSourceMeta, var fields: List<SourceSetting> = metadata.declaredSettings().ifEmpty { commonSettings })

    private val sources = linkedMapOf<String, Source>()
    private val engines = linkedMapOf<String, JsSourceEngine>()
    private val covers = linkedMapOf<Pair<String, String>, Pair<Long, ListeningResource?>>()
    private val coverRequests = mutableMapOf<Pair<String, String>, Deferred<ListeningResource?>>()
    private val coverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val coverPermits = Semaphore(4)
    private var coverRevision = 0L
    private val client = OkHttpClient()
    private val mutex = Mutex()

    fun handles(id: String): Boolean = id.startsWith("jdr:")

    suspend fun load(entry: String, bytes: ByteArray): List<ListeningSource> = mutex.withLock {
        val archive = JdrArchive.parse(bytes)
        require(entry == "jdr:${archive.manifest.id}") { "JDR 包标识不匹配，请重新导入" }
        val entries = archive.manifest.sources.map { metadata ->
            ListeningSource("jdr:${metadata.id}", metadata.name, archive.manifest.description, entry, metadata.capabilities.toSet())
        }
        require(entries.none { it.id in sources }) { "书源 ID 重复" }
        entries.zip(archive.manifest.sources).forEach { (entry, metadata) ->
            // 已发布的 1.1.4 网盘包漏写天翼起点，兼容旧包时保留用户配置。
            val compatible = if (archive.isLegacyNetdisk() && metadata.id == "cloud189") metadata.copy(initialDirectory = "-11") else metadata
            sources[entry.id] = Source(archive, compatible)
        }
        entries
    }

    suspend fun clear() = mutex.withLock {
        engines.values.forEach { it.close() }
        engines.clear()
        sources.clear()
        invalidateCovers()
    }

    suspend fun search(id: String, keyword: String, page: Int): Pair<List<Book>, Int> {
        // Timbre 的搜索接口没有总页数字段，只请求第一页。
        if (page != 1) return emptyList<Book>() to 1
        val result = invoke(id, "search", params(id, mapOf("keyword" to keyword, "page" to 1, "limit" to 30)), 15_000)
        val canResolveCover = supportsCover(id)
        return SourceContract.parseSearchResults(result).map { item ->
            Book(coverReference(canResolveCover, item.id, item.extra, item.cover), item.id, item.title, item.author, "").apply {
                sourceId = id
                intro = item.intro
                jdrExtras = item.extra.toString()
            }
        } to 1
    }

    suspend fun detail(id: String, book: Book, key: String): ListeningBook {
        val result = invoke(
            id,
            "chapters",
            params(id, mapOf("bookId" to book.bookUrl, "page" to 1, "size" to 0), book.jdrExtras),
            // 网盘长书需要分页并限速等待，给完整章节收集留足时间。
            if (mutex.withLock { requireSource(id).archive.isLegacyNetdisk() }) 120_000 else 20_000,
        )
        val chapters = SourceContract.parseChapters(result)
        return ListeningBook(
            key, id, book.bookUrl, book.title, book.coverUrl, book.intro,
            chapters.map { Episode(it.title, it.id) },
            book.jdrExtras,
            chapters.associate { it.id to it.extra.toString() },
        )
    }

    suspend fun resolve(book: ListeningBook, index: Int): ListeningResource {
        val chapter = book.episodes[index]
        val result = invoke(
            book.sourceId,
            "audio",
            params(book.sourceId, mapOf("bookId" to book.url, "chapterId" to chapter.url), book.jdrChapterExtras[chapter.url] ?: "{}"),
            30_000,
        )
        val audio = SourceContract.parseAudio(result)
        return ListeningResource(audio.url, audio.headers)
    }

    private suspend fun invoke(id: String, stage: String, params: String, timeout: Long): String = mutex.withLock {
        val source = checkNotNull(sources[id]) { "书源已禁用、已删除或加载失败" }
        val engine = engine(id, source)
        require(stage in source.metadata.capabilities || stage in engine.features()) { "该书源不支持 $stage" }
        if (source.metadata.id == "pan123") {
            val config = (Json.parseToJsonElement(params) as? JsonObject)?.get("config") as? JsonObject

            // 只记录是否读到凭证，便于排查配置丢失，不记录账号和令牌。
            fun filled(key: String): Boolean = (config?.get(key) as? JsonPrimitive)?.content?.isNotBlank() == true
            Log.i("JdrSource", "pan123 stage=$stage account=${filled("passport")} password=${filled("password")} token=${filled("token")}")
        }
        try {
            engine.invoke(stage, params, timeout)
        } catch (error: Exception) {
            Log.w("JdrSource", "${source.metadata.id} stage=$stage failed: ${ListeningErrors.sanitize(error.message.orEmpty())}")
            if (error is CancellationException) engines.remove(id)?.close()
            throw error
        }
    }

    private suspend fun engine(id: String, source: Source): JsSourceEngine = engines[id] ?: JsSourceEngine.create(
        source.metadata.id,
        sourceScript(source),
        source.metadata.script,
        OkHttpSandboxHttp(httpClient(source.archive.manifest.allowInsecure)),
        host = preferences(source),
    ).also { engines[id] = it }
    suspend fun configuration(id: String): JdrConfiguration = mutex.withLock {
        val source = requireSource(id)
        val engine = engine(id, source)
        val features = engine.features()
        val fields = if ("config" in features) {
            SourceFeatures.parseSettings(engine.invoke("config", "{\"action\":\"get\"}", 30_000), "browse" in features)
        } else {
            emptyList()
        }
        val merged = (source.metadata.declaredSettings() + fields).associateBy { it.key }.values.toList().ifEmpty {
            commonSettings.map { if (it.key == "root" && "browse" in features) it.copy(type = "directory", default = source.metadata.initialDirectory) else it }
        }
        SourceFeatures.validateSettings(merged, "browse" in features)
        if (source.fields != merged) {
            source.fields = merged
            engines.remove(id)?.close()
        }
        JdrConfiguration(source.fields, preferences(source).values(), "login" in features, "browse" in features, source.metadata.initialDirectory)
    }

    suspend fun saveConfiguration(id: String, values: Map<String, String>) = mutex.withLock {
        val source = requireSource(id)
        preferences(source).save(values)
        invalidateCovers()
        val engine = engine(id, source)
        try {
            if ("config" in engine.features()) engine.invoke("config", JsonObject(mapOf("action" to JsonPrimitive("save"), "values" to JsonObject(values.mapValues { JsonPrimitive(it.value) }))).toString(), 30_000)
        } finally {
            engines.remove(id)?.close()
        }
    }

    suspend fun configAction(id: String, action: String, values: Map<String, String>): String {
        val config = configuration(id)
        require(config.fields.any { it.type == "button" && (it.action.ifBlank { it.key }) == action }) { "未知配置操作" }
        saveConfiguration(id, values)
        val result = invoke(id, "config", JsonObject(mapOf("action" to JsonPrimitive(action), "values" to JsonObject(values.mapValues { JsonPrimitive(it.value) }))).toString(), 30_000)
        val data = Json.parseToJsonElement(result)
        return when (data) {
            is JsonPrimitive -> if (data.toString() == "null") "操作完成" else data.content
            is JsonObject -> (data["message"] as? JsonPrimitive)?.content ?: "操作完成"
            else -> "操作完成"
        }
    }

    suspend fun login(id: String, action: String, state: JsonObject = JsonObject(emptyMap()), cookies: String = ""): SourceLogin {
        require(action in setOf("status", "login", "poll", "webComplete", "logout")) { "未知登录操作" }
        // 同样要带 config：源在 status/webComplete 里靠它判断「凭证是否已填写」
        val params = params(
            id,
            mapOf("action" to action, "state" to state, "cookies" to cookies),
        )
        val result = SourceFeatures.parseLogin(invoke(id, "login", params, 30_000))
        if (action == "logout" && result.authenticated == false) {
            mutex.withLock {
                preferences(requireSource(id)).clear()
                invalidateCovers()
                engines.remove(id)?.close()
            }
        }
        return result
    }

    suspend fun browse(id: String, directory: String?, page: Int): JdrBrowserPage {
        val root = mutex.withLock {
            val source = requireSource(id)
            source.fields.firstOrNull { it.type == "directory" }?.let { preferences(source).values()[it.key] }
                ?.takeIf { it.isNotBlank() } ?: preferences(source).values()["root"]?.takeIf { it.isNotBlank() } ?: source.metadata.initialDirectory
        }
        val selected = directory ?: root
        val result = SourceFeatures.parseDirectory(invoke(id, "browse", params(id, mapOf("directoryId" to selected, "page" to page, "limit" to 100)), 30_000), page)
        val canResolveCover = supportsCover(id)
        val books = result.items.filterNot { it.directory }.map { item ->
            // 旧包 browse 返回裸目录 ID，chapters/cover 却要求 D_ 前缀。
            val bookId = if (requireSource(id).archive.isLegacyNetdisk() && !item.id.startsWith("D_")) "D_${item.id}" else item.id
            val extra = item.extra + mapOf("id" to JsonPrimitive(bookId), "bookTitle" to JsonPrimitive(item.name))
            val parsed = SourceContract.parseSearchResults(JsonArray(listOf(JsonObject(extra))).toString()).single()
            Book(coverReference(canResolveCover, parsed.id, parsed.extra, parsed.cover), parsed.id, parsed.title, parsed.author, "").apply {
                sourceId = id
                intro = parsed.intro
                jdrExtras = parsed.extra.toString()
            }
        }
        return JdrBrowserPage(selected, result.items.filter { it.directory }.map { JdrFolder(it.id, it.name) }, books, result.nextPage)
    }

    suspend fun clearMetadataCache(id: String) = mutex.withLock {
        preferences(requireSource(id)).clearCache()
        invalidateCovers()
    }

    private suspend fun supportsCover(id: String): Boolean = mutex.withLock { "cover" in engine(id, requireSource(id)).features() }

    private fun coverReference(canResolveCover: Boolean, bookId: String, extra: JsonObject, fallback: String): String {
        if (!canResolveCover) return fallback
        return Uri.Builder().scheme("jdr-cover").authority("book")
            .appendQueryParameter("bookId", bookId).appendQueryParameter("extras", extra.toString())
            .appendQueryParameter("fallback", fallback).build().toString()
    }

    suspend fun resolveCover(id: String, reference: String): ListeningResource? {
        if (!reference.startsWith("jdr-cover:")) return ListeningResource(reference, emptyMap())
        val key = id to reference
        val request = mutex.withLock {
            covers[key]?.let { (expires, resource) ->
                if (expires > android.os.SystemClock.elapsedRealtime()) return resource
            }
            coverRequests.getOrPut(key) {
                val source = requireSource(id)
                val host = preferences(source)
                val revision = coverRevision
                val uri = Uri.parse(reference)
                val bookId = uri.getQueryParameter("bookId") ?: return null
                val parameters = params(id, mapOf("bookId" to bookId), uri.getQueryParameter("extras") ?: "{}")
                coverScope.async(start = CoroutineStart.LAZY) {
                    try {
                        // 已核对的网盘脚本从持久状态恢复会话，可用独立沙箱并行查询。
                        // 其他脚本保留原沙箱，兼容封面依赖 browse 内存状态的实现。
                        val result = coverPermits.withPermit {
                            if (source.archive.manifest.id == "com.timbre.tingshu-netdisk" && source.archive.manifest.version in setOf("1.1.4", "1.1.5")) {
                                JsSourceEngine.create(
                                    source.metadata.id,
                                    coverScript(source),
                                    source.metadata.script,
                                    OkHttpSandboxHttp(httpClient(source.archive.manifest.allowInsecure)),
                                    host = host,
                                ).use { engine -> engine.invoke("cover", parameters, 30_000) }
                            } else {
                                invoke(id, "cover", parameters, 30_000)
                            }
                        }
                        val resource = if (result == "null") {
                            uri.getQueryParameter("fallback")?.takeIf { it.isNotBlank() }?.let { ListeningResource(it, emptyMap()) }
                        } else {
                            val cover = SourceContract.parseAudio(result)
                            ListeningResource(cover.url, cover.headers)
                        }
                        mutex.withLock {
                            if (coverRevision == revision) {
                                covers[key] = (android.os.SystemClock.elapsedRealtime() + if (resource == null) 60_000 else 300_000) to resource
                                while (covers.size > 128) covers.remove(covers.keys.first())
                            }
                        }
                        resource
                    } finally {
                        val job = currentCoroutineContext()[Job]
                        withContext(NonCancellable) {
                            mutex.withLock { if (coverRequests[key] === job) coverRequests.remove(key) }
                        }
                    }
                }
            }
        }
        return request.await()
    }

    /** 调用方持有状态锁，取消旧凭证下的查询，防止旧结果回填。 */
    private fun invalidateCovers() {
        coverRevision++
        covers.clear()
        coverRequests.values.forEach { it.cancel() }
        coverRequests.clear()
    }

    private fun coverScript(source: Source): String {
        val script = sourceScript(source)
        // 123 的目录响应已有图片 DownloadUrl，直接复用，省去再次解析下载地址。
        return if (source.metadata.id == "pan123") {
            script.replace("String(picked.Thumbnail || picked.thumbnail || '')", "String(picked.Thumbnail || picked.thumbnail || picked.DownloadUrl || picked.downloadUrl || '')")
        } else {
            script
        }
    }

    private fun sourceScript(source: Source): String {
        val script = source.archive.scriptFor(source.metadata)
        return if (source.metadata.id == "pan123" && source.archive.manifest.id == "com.timbre.tingshu-netdisk" && source.archive.manifest.version in setOf("1.1.4", "1.1.5")) {
            Pan123ScriptCompatibility.script(script)
        } else {
            script
        }
    }

    private fun requireSource(id: String): Source = checkNotNull(sources[id]) { "书源已禁用、已删除或加载失败" }
    private fun JdrArchive.isLegacyNetdisk(): Boolean = manifest.id == "com.timbre.tingshu-netdisk" && manifest.version == "1.1.4"
    private fun preferences(source: Source) = JdrSourcePreferences(context, source.archive.manifest.id + ":" + source.metadata.id, source.fields)

    /**
     * 组装单个 stage 的入参。
     *
     * `config` 必须在这里注入：书源把它当作用户配置的唯一入口
     * （`params.config.rootDir`、`params.config.passport` 等），
     * 宿主契约里 config 是**总是**存在的对象，没配置时为空对象。
     */
    private fun params(id: String, base: Map<String, Any>, extras: String = "{}"): String {
        val merged = Json.parseToJsonElement(extras) as? JsonObject ?: error("无效的 JDR 附加数据")
        val baseWithConfig = JsonObject(
            merged + base.mapValues { (_, value) ->
                when (value) {
                    is JsonElement -> value
                    is Number -> JsonPrimitive(value)
                    is Boolean -> JsonPrimitive(value)
                    else -> JsonPrimitive(value.toString())
                }
            } + ("config" to Json.parseToJsonElement(preferences(requireSource(id)).settings())),
        )
        return baseWithConfig.toString()
    }

    companion object {
        private val commonSettings = listOf(
            SourceSetting("username", "账号"),
            SourceSetting("password", "密码", "password"),
            SourceSetting("cookie", "登录 Cookie", "password"),
            SourceSetting("token", "登录 Token", "password"),
            SourceSetting("root", "听书路径"),
        )
    }

    /** 仅在源包显式声明 allowInsecure 时兼容其 TLS 设置。 */
    private fun httpClient(allowInsecure: Boolean): OkHttpClient {
        if (!allowInsecure) return client
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), SecureRandom()) }
        return client.newBuilder().sslSocketFactory(ssl.socketFactory, trust).hostnameVerifier { _, _ -> true }.build()
    }
}
