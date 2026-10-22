package com.fluxplayer.app.core.tingshu

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.fluxplayer.app.core.common.ListeningHistoryReset
import com.github.eprendre.tingshu.sources.AudioUrlExtraHeaders
import com.github.eprendre.tingshu.sources.ConfigurableSource
import com.github.eprendre.tingshu.sources.CoverUrlExtraHeaders
import com.github.eprendre.tingshu.sources.TingShu
import com.github.eprendre.tingshu.sources.WebLoginTokenSource
import com.github.eprendre.tingshu.utils.Book
import com.github.eprendre.tingshu.utils.BookDetail
import com.github.eprendre.tingshu.utils.Category
import com.github.eprendre.tingshu.utils.CategoryMenu
import com.github.eprendre.tingshu.utils.ConfigItem
import com.github.eprendre.tingshu.utils.Episode
import dalvik.system.DexClassLoader
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class SourcePackage(
    val entry: String,
    val file: String,
    val enabled: Boolean = true,
    val error: String? = null,
    val name: String = "",
    val sources: List<ListeningSource> = emptyList(),
) {
    val displayName: String get() = name.ifBlank { sources.singleOrNull()?.name ?: entry.removePrefix("jdr:") }
}

data class ListeningSource(val id: String, val name: String, val description: String, val packageEntry: String, val capabilities: Set<String> = emptySet())

/** 集合包里已经校验通过、等待一起提交的单个 JAR。 */
private class StagedPackage(
    val entry: String,
    val file: File,
    val sources: List<Pair<ListeningSource, TingShu>>,
)

data class ListeningBook(
    val key: String,
    val sourceId: String,
    val url: String,
    val title: String,
    val coverUrl: String,
    val intro: String,
    val episodes: List<Episode>,
    val jdrBookExtras: String = "{}",
    val jdrChapterExtras: Map<String, String> = emptyMap(),
)

data class ListeningProgress(val episodeUrl: String = "", val position: Long = 0, val duration: Long = 0)

data class ListeningResource(val url: String, val headers: Map<String, String>)

/** 所有书源调用在同一后台线程执行，隔离旧接口的共享单例和同步请求。 */
class TingshuRepository private constructor(private val context: Context) {
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val directory = File(context.filesDir, "tingshu/packages").apply { mkdirs() }
    private val bookDirectory = File(context.filesDir, "tingshu/books").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("tingshu_library", Context.MODE_PRIVATE)
    private val progressLock = Any()
    private val loaded = linkedMapOf<String, TingShu>()
    private val jdr = JdrSourceBackend(context)
    private val packageMutex = Mutex()
    private val _packages = MutableStateFlow<List<SourcePackage>>(emptyList())
    val packages = _packages.asStateFlow()
    private val _sources = MutableStateFlow<List<ListeningSource>>(emptyList())
    val sources = _sources.asStateFlow()
    private val _progresses = MutableStateFlow<Map<String, ListeningProgress>>(emptyMap())
    val progresses = _progresses.asStateFlow()
    private val _recentBooks = MutableStateFlow<List<ListeningBook>>(emptyList())
    val recentBooks = _recentBooks.asStateFlow()

    init {
        SourceHost.initialize(context)
        scope.launch {
            // 历史只读取本地快照，先恢复进度再发布书籍，不等待书源加载或网络请求。
            val keys = runCatching { readRecentKeys() }.getOrDefault(emptyList()).distinct().take(20)
            val books = keys.mapNotNull { key -> runCatching { book(key) }.getOrNull() }
            _progresses.value = keys.mapNotNull { key ->
                runCatching { readProgress(key) }.getOrNull()?.let { key to it }
            }.toMap() + _progresses.value
            _recentBooks.value = books
            managePackages { reload(readPackages()) }
        }
    }

    suspend fun importJar(uri: Uri) = importSource(uri)

    suspend fun importSource(uri: Uri): Unit = managePackages {
        val filename = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()
        if (filename.endsWith(".jdr", ignoreCase = true)) {
            importJdr(uri)
            return@managePackages
        }
        if (filename.endsWith(".zip", ignoreCase = true)) {
            importBundle(uri)
            return@managePackages
        }
        val entry = filename.removeSuffix(".jar")
        require(filename.endsWith(".jar") && entry.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
            "请选择 Timbre .jdr 或保留原文件名的 JAR，例如 sources_by_pan123.jar"
        }
        val destination = File(directory, "$entry-${UUID.randomUUID()}.jar")
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "无法读取所选文件" }
                writeJarFile(input, destination, MAX_JAR_SIZE, "书源包不能超过 20 MB")
            }
            validateDex(destination)
            val imported = loadPackage(SourcePackage(entry, destination.name))
            val otherIds = _sources.value.filter { it.packageEntry != entry }.map { it.id }.toSet()
            require(imported.none { it.first.id in otherIds }) { "书源 ID 与已导入的其他包重复" }
            val previous = readPackages()
            val replacement = previous.filter { it.entry != entry } + SourcePackage(entry, destination.name)
            persistPackages(replacement)
            reload(replacement)
            previous.filter { it.entry == entry }.forEach { File(directory, it.file).delete() }
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    suspend fun setEnabled(entry: String, enabled: Boolean) = managePackages {
        val updated = readPackages().map { if (it.entry == entry) it.copy(enabled = enabled) else it }
        persistPackages(updated)
        reload(updated)
    }

    suspend fun remove(entry: String) = managePackages {
        val previous = readPackages()
        val updated = previous.filter { it.entry != entry }
        persistPackages(updated)
        reload(updated)
        previous.filter { it.entry == entry }.forEach { File(directory, it.file).delete() }
    }

    suspend fun menus(sourceId: String): List<CategoryMenu> = withContext(dispatcher) {
        if (jdr.handles(sourceId)) {
            emptyList()
        } else {
            SourceHost.captureMessages { messages ->
                source(sourceId).getCategoryMenus().also { if (it.isEmpty() && messages.isNotEmpty()) error(messages.last()) }
            }
        }
    }

    suspend fun category(sourceId: String, url: String): Category = withContext(dispatcher) {
        SourceHost.captureMessages { messages ->
            source(sourceId).getCategoryList(url).also { if (it.list.isEmpty() && messages.isNotEmpty()) error(messages.last()) }
        }
    }

    suspend fun search(sourceId: String, keywords: String, page: Int): Pair<List<Book>, Int> = withContext(dispatcher) {
        if (jdr.handles(sourceId)) return@withContext jdr.search(sourceId, keywords, page)
        val selected = source(sourceId)
        require(selected.isSearchable()) { "该书源不支持搜索" }
        selected.search(keywords, page)
    }

    suspend fun detail(sourceId: String, book: Book, refreshMetadata: Boolean = false): ListeningBook = withContext(dispatcher) {
        if (jdr.handles(sourceId)) {
            val latest = if (refreshMetadata) jdr.refreshMetadata(sourceId, book) else book
            val snapshot = jdr.detail(sourceId, latest, digest("$sourceId\u0000${book.bookUrl}"))
            require(snapshot.episodes.isNotEmpty()) { "书源没有返回可播放章节" }
            writeBook(snapshot)
            _recentBooks.value = _recentBooks.value.map { if (it.key == snapshot.key) snapshot else it }
            return@withContext snapshot
        }
        val selected = source(sourceId)
        SourceHost.currentBook.set(book)
        try {
            val detail: BookDetail = SourceHost.captureMessages { messages ->
                selected.getBookDetailInfo(book.bookUrl, true, true).also {
                    require(it.playList.isNotEmpty()) { messages.lastOrNull() ?: "书源没有返回可播放章节" }
                }
            }
            val snapshot = ListeningBook(
                key = digest("$sourceId\u0000${book.bookUrl}"),
                sourceId = sourceId,
                url = book.bookUrl,
                title = detail.title.ifBlank { book.title },
                coverUrl = detail.coverUrl.ifBlank { book.coverUrl },
                intro = detail.intro.orEmpty().ifBlank { book.intro },
                episodes = detail.playList,
            )
            writeBook(snapshot)
            _recentBooks.value = _recentBooks.value.map { if (it.key == snapshot.key) snapshot else it }
            snapshot
        } finally {
            SourceHost.currentBook.remove()
        }
    }

    suspend fun book(key: String): ListeningBook = withContext(dispatcher) {
        require(key.matches(Regex("[a-f0-9]{64}"))) { "无效的书籍标识" }
        val data = JSONObject(File(bookDirectory, "$key.json").readText())
        ListeningBook(
            key,
            data.getString("sourceId"),
            data.getString("url"),
            data.getString("title"),
            data.getString("coverUrl"),
            data.getString("intro"),
            data.getJSONArray("episodes").let { list ->
                List(list.length()) { index ->
                    val item = list.getJSONObject(index)
                    Episode(item.getString("title"), item.getString("url"))
                }
            },
            data.optString("jdrBookExtras", "{}"),
            data.optJSONObject("jdrChapterExtras")?.let { extras ->
                extras.keys().asSequence().associateWith { extras.getString(it) }
            }.orEmpty(),
        )
    }

    /** 有持久化详情时先填充弹窗，缺失时仍由书源正常解析。 */
    suspend fun cachedBook(sourceId: String, url: String): ListeningBook? = withContext(dispatcher) {
        val key = digest("$sourceId\u0000$url")
        try {
            if (!File(bookDirectory, "$key.json").isFile) null else book(key)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }
    }

    suspend fun refreshBook(book: ListeningBook): ListeningBook {
        val refreshed = detail(book.sourceId, Book(book.coverUrl, book.url, book.title, "", book.intro).apply { jdrExtras = book.jdrBookExtras }, refreshMetadata = true)
        _recentBooks.value = _recentBooks.value.map { if (it.key == book.key) refreshed else it }
        return refreshed
    }

    /** 备份书籍目录与收听偏好，不包含书源登录凭证和临时播放地址。 */
    suspend fun exportLibrary(): String = withContext(dispatcher) {
        val library = JSONObject()
        prefs.all.forEach { (key, value) ->
            if (key == "recentBooks" || key.startsWith("progress.") || key.startsWith("chapters.") || key.startsWith("playedAt.")) library.put(key, value)
        }
        val playback = JSONObject()
        context.getSharedPreferences("tingshu_playback", Context.MODE_PRIVATE).all.forEach { (key, value) ->
            if (key == "speed" || key.endsWith(".intro") || key.endsWith(".outro")) playback.put(key, value)
        }
        val books = JSONObject()
        bookDirectory.listFiles().orEmpty().filter { it.extension == "json" }.forEach { books.put(it.nameWithoutExtension, JSONObject(it.readText())) }
        JSONObject().put("version", 1).put("library", library).put("playback", playback).put("books", books).toString()
    }

    suspend fun restoreLibrary(raw: String) = withContext(dispatcher) {
        val data = JSONObject(raw)
        require(data.getInt("version") == 1) { "不支持的听书备份版本" }
        val books = data.getJSONObject("books")
        // 先校验全部数据，避免损坏的备份写入一部分后才失败。
        val entries = books.keys().asSequence().map { key ->
            require(key.matches(Regex("[a-f0-9]{64}"))) { "无效的备份书籍标识" }
            val book = books.getJSONObject(key)
            book.getString("sourceId")
            book.getString("url")
            book.getString("title")
            book.getJSONArray("episodes")
            key to book.toString()
        }.toList()
        val library = data.getJSONObject("library")
        library.keys().asSequence().forEach { key ->
            if (key.startsWith("progress.") || key.startsWith("chapters.")) JSONObject(library.getString(key))
        }
        entries.forEach { (key, value) ->
            val temporary = File(bookDirectory, "$key.tmp")
            temporary.writeText(value)
            check(temporary.renameTo(File(bookDirectory, "$key.json"))) { "恢复书籍目录失败" }
        }
        prefs.edit().apply {
            library.keys().asSequence().forEach { key ->
                when {
                    key == "recentBooks" || key.startsWith("progress.") || key.startsWith("chapters.") -> putString(key, library.getString(key))
                    key.startsWith("playedAt.") -> putLong(key, library.getLong(key))
                }
            }
        }.apply()
        val playback = data.optJSONObject("playback") ?: JSONObject()
        context.getSharedPreferences("tingshu_playback", Context.MODE_PRIVATE).edit().apply {
            playback.keys().asSequence().forEach { key ->
                when {
                    key == "speed" -> putFloat(key, playback.getDouble(key).toFloat().coerceIn(0.5f, 4f))
                    key.endsWith(".intro") || key.endsWith(".outro") -> putInt(key, playback.getInt(key).coerceIn(0, 300))
                }
            }
        }.apply()
        _recentBooks.value = readRecentKeys().mapNotNull { runCatching { book(it) }.getOrNull() }
        _progresses.value = _recentBooks.value.mapNotNull { book -> readProgress(book.key)?.let { book.key to it } }.toMap()
    }

    suspend fun resolve(book: ListeningBook, index: Int): ListeningResource = withContext(dispatcher) {
        if (jdr.handles(book.sourceId)) return@withContext jdr.resolve(book, index)
        val selected = source(book.sourceId)
        val episode = book.episodes[index]
        SourceHost.currentBook.set(
            Book(book.coverUrl, book.url, book.title, "", "").apply {
                sourceId = book.sourceId
                currentEpisodeUrl = episode.url
                currentEpisodeName = episode.title
            },
        )
        SourceHost.extractedUrl.remove()
        try {
            val url = SourceHost.captureMessages { messages ->
                selected.getAudioUrlExtractor().extract(episode.url, true, false, false)
                checkNotNull(SourceHost.extractedUrl.get()) { messages.lastOrNull() ?: "书源没有返回播放地址，请重试或检查书源配置" }
            }
            require(Uri.parse(url).scheme in setOf("http", "https")) { "书源返回的地址不是 HTTP 音频地址" }
            ListeningResource(url, (selected as? AudioUrlExtraHeaders)?.headers(url).orEmpty())
        } finally {
            SourceHost.currentBook.remove()
            SourceHost.extractedUrl.remove()
        }
    }

    suspend fun coverHeaders(sourceId: String, url: String): Map<String, String> = withContext(dispatcher) {
        if (jdr.handles(sourceId)) return@withContext emptyMap()
        val result = mutableMapOf<String, String>()
        (source(sourceId) as? CoverUrlExtraHeaders)?.coverHeaders(url, result)
        result
    }

    suspend fun resolveCover(sourceId: String, url: String, forceRefresh: Boolean = false): ListeningResource? = withContext(dispatcher) {
        if (jdr.handles(sourceId)) {
            if (forceRefresh) jdr.invalidateCover(sourceId, url)
            jdr.resolveCover(sourceId, url)
        } else {
            ListeningResource(url, coverHeaders(sourceId, url))
        }
    }

    suspend fun playbackHeaders(sourceId: String, url: String): Map<String, String> = withContext(dispatcher) {
        if (jdr.handles(sourceId)) emptyMap() else (source(sourceId) as? AudioUrlExtraHeaders)?.headers(url).orEmpty()
    }

    fun lastPlayedAt(key: String): Long = prefs.getLong("playedAt.$key", 0L)

    suspend fun markPlayed(book: ListeningBook, playedAt: Long = System.currentTimeMillis()) = withContext(dispatcher) {
        synchronized(progressLock) {
            if (!ListeningHistoryReset.accepts(playedAt, ListeningHistoryReset.cutoff(context, book.key))) return@synchronized
            prefs.edit().putLong("playedAt.${book.key}", playedAt).apply()
            val books = (listOf(book) + _recentBooks.value.filter { it.key != book.key }).take(20)
            prefs.edit().putString("recentBooks", JSONArray(books.map { it.key }).toString()).apply()
            _recentBooks.value = books
        }
    }

    suspend fun clearHistory(key: String? = null, now: Long = System.currentTimeMillis()) = withContext(dispatcher) {
        synchronized(progressLock) {
            ListeningHistoryReset.record(context, key, now)
            val editor = prefs.edit()
            if (key == null) {
                prefs.all.keys.filter { it.startsWith("progress.") || it.startsWith("chapters.") || it.startsWith("playedAt.") }.forEach(editor::remove)
                _progresses.value = emptyMap()
                _recentBooks.value = emptyList()
            } else {
                editor.remove("progress.$key").remove("chapters.$key").remove("playedAt.$key")
                _progresses.value = _progresses.value - key
                _recentBooks.value = _recentBooks.value.filter { it.key != key }
            }
            check(editor.putString("recentBooks", JSONArray(_recentBooks.value.map { it.key }).toString()).commit()) { "清除收听进度失败" }
        }
    }

    private fun readRecentKeys(): List<String> {
        val keys = JSONArray(prefs.getString("recentBooks", "[]"))
        return List(keys.length()) { keys.getString(it) }
    }

    suspend fun clearJdrMetadataCache(id: String) = withContext(dispatcher) { jdr.clearMetadataCache(id) }
    suspend fun jdrConfiguration(id: String) = withContext(dispatcher) {
        jdr.configuration(id).also { config ->
            _sources.value = _sources.value.map { source ->
                if (source.id == id) source.copy(capabilities = source.capabilities + listOfNotNull("login".takeIf { config.canLogin }, "browse".takeIf { config.canBrowse })) else source
            }
        }
    }
    suspend fun saveJdrConfiguration(id: String, values: Map<String, String>) = withContext(dispatcher) { jdr.saveConfiguration(id, values) }
    suspend fun jdrLogin(id: String, action: String, state: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()), cookies: String = "", webToken: String = "") =
        withContext(dispatcher) { jdr.login(id, action, state, cookies, webToken) }
    suspend fun jdrConfigAction(id: String, action: String, values: Map<String, String>) = withContext(dispatcher) { jdr.configAction(id, action, values) }
    suspend fun browseJdr(id: String, directory: String?, page: Int = 1) = withContext(dispatcher) { jdr.browse(id, directory, page) }

    /**
     * 取书源的 WebView 登录页信息（URL + UA + 登录态探测规则）。
     * 天翼/夸克/移动等网盘源实现了 ILogin，仅 JAR 书源走这条路径；
     * JDR 源有各自的登录实现，不在此列。书源未给出可用地址时返回 null，由调用方降级提示。
     */
    suspend fun loginInfo(sourceId: String): SourceHost.LoginInfo? = withContext(dispatcher) {
        if (jdr.handles(sourceId)) return@withContext null
        // 139/189 的 getLoginUrl() 里会 showToast 提示「登录成功后返回 App」，
        // 那是点进登录页之后的提示；这里只是探测有没有登录入口，不该提前弹给用户。
        runCatching { SourceHost.captureMessages { SourceHost.loginInfo(source(sourceId)) } }.getOrNull()
    }

    suspend fun completeWebLogin(sourceId: String, tokenJson: String) = withContext(dispatcher) {
        val source = source(sourceId) as? WebLoginTokenSource ?: error("该书源不支持网页登录凭证")
        source.onWebLoginToken(tokenJson)
    }

    suspend fun config(sourceId: String): List<ConfigItem> = withContext(dispatcher) {
        if (!jdr.handles(sourceId)) return@withContext (source(sourceId) as? ConfigurableSource)?.getCustomConfigItems().orEmpty()
        val config = jdr.configuration(sourceId)
        config.fields.map { field ->
            val value = config.values[field.key].orEmpty()
            when (field.type) {
                "switch" -> ConfigItem.Switch(field.key, field.label, value == "true")
                "select" -> ConfigItem.Select(field.key, field.label, field.options, value)
                "multiselect" -> ConfigItem.MultiSelect(field.key, field.label, field.options, value.split(',').filter { it.isNotBlank() })
                "button" -> ConfigItem.Button(field.label) {
                    kotlinx.coroutines.runBlocking {
                        jdr.configAction(sourceId, field.action.ifBlank { field.key }, jdr.configuration(sourceId).values)
                    }
                }
                else -> ConfigItem.Text(field.key, field.label, value)
            }
        }
    }

    suspend fun configAction(action: () -> Unit): String? = withContext(dispatcher) {
        SourceHost.captureMessages { messages ->
            action()
            messages.lastOrNull()
        }
    }

    suspend fun saveConfig(sourceId: String, values: Map<String, String>) = withContext(dispatcher) {
        if (jdr.handles(sourceId)) {
            jdr.saveConfiguration(sourceId, values)
            return@withContext
        }
        source(sourceId).reset()
        val editor = context.getSharedPreferences("tingshu_source_config", Context.MODE_PRIVATE).edit()
        values.forEach { (key, value) -> editor.putString("$sourceId.$key", value) }
        check(editor.commit()) { "书源配置保存失败" }
    }

    fun progress(key: String): ListeningProgress {
        val raw = prefs.getString("progress.$key", null) ?: return ListeningProgress()
        val value = JSONObject(raw)
        return ListeningProgress(value.optString("url"), value.optLong("position"), value.optLong("duration"))
    }

    /** 读取磁盘进度，无记录或数据损坏时返回 null（区别于「有记录但进度为 0」）。 */
    private fun readProgress(key: String): ListeningProgress? {
        val raw = prefs.getString("progress.$key", null) ?: return null
        return runCatching {
            val value = JSONObject(raw)
            ListeningProgress(value.optString("url"), value.optLong("position"), value.optLong("duration"))
        }.getOrNull()
    }

    fun chapterProgress(book: ListeningBook): Map<Int, Pair<Long, Long>> {
        val values = JSONObject(prefs.getString("chapters.${book.key}", "{}") ?: "{}")
        val latest = progress(book.key)
        return book.episodes.mapIndexedNotNull { index, episode ->
            val value = values.optJSONObject(episode.url)
            when {
                value != null -> index to (value.optLong("position") to value.optLong("duration"))
                episode.url == latest.episodeUrl -> index to (latest.position to latest.duration)
                else -> null
            }
        }.toMap()
    }

    fun saveProgress(key: String, progress: ListeningProgress, playedAt: Long = System.currentTimeMillis()) = synchronized(progressLock) {
        if (!ListeningHistoryReset.accepts(playedAt, ListeningHistoryReset.cutoff(context, key))) return@synchronized
        val chapters = JSONObject(prefs.getString("chapters.$key", "{}") ?: "{}")
        chapters.put(progress.episodeUrl, JSONObject().put("position", progress.position.coerceAtLeast(0)).put("duration", progress.duration.coerceAtLeast(0)))
        prefs.edit().putString("chapters.$key", chapters.toString()).apply()
        prefs.edit().putString(
            "progress.$key",
            JSONObject().put("url", progress.episodeUrl).put("position", progress.position.coerceAtLeast(0))
                .put("duration", progress.duration.coerceAtLeast(0)).toString(),
        ).apply()
        _progresses.value = _progresses.value + (key to progress)
    }

    private suspend fun <T> managePackages(block: suspend () -> T): T = withContext(dispatcher) {
        packageMutex.withLock { block() }
    }

    private fun source(id: String): TingShu = checkNotNull(loaded[id]) { "书源已禁用、已删除或加载失败" }

    private fun packageFile(pkg: SourcePackage): File {
        val file = File(directory, pkg.file)
        require(file.canonicalFile.parentFile == directory.canonicalFile && file.isFile) { "书源文件不存在" }
        require(file.length() <= MAX_JAR_SIZE) { "书源包不能超过 20 MB" }
        return file
    }

    /**
     * 导入书源集合包：一个 .zip 里放多个 `sources_by_*.jar`。
     *
     * 每个 JAR 都要过和单包导入完全一样的校验（保留原名、20 MB 上限、DEX 有效、书源 ID 不重复），
     * 全部通过才一起落盘；任何一个不合法就整体回滚，不会留下「导了一半」的书源列表。
     * 压缩包允许带子目录（例如 `jars/sources_by_quark.jar`），只按文件名判断包名。
     */
    private suspend fun importBundle(uri: Uri) {
        val workspace = File(context.cacheDir, "source-bundle-${UUID.randomUUID()}").apply { mkdirs() }
        val staged = mutableListOf<StagedPackage>()
        try {
            val archive = File(workspace, "bundle.zip")
            copyUri(uri, archive, MAX_BUNDLE_SIZE, "书源集合包不能超过 ${MAX_BUNDLE_SIZE / (1024 * 1024)} MB")
            ZipFile(archive).use { zip ->
                val members = zip.entries().asSequence()
                    .filter { !it.isDirectory }
                    // macOS 打包会在每层目录塞 __MACOSX/._xxx.jar，不排除会当成坏包
                    .filter { !it.name.contains("__MACOSX") }
                    .filter { jarBaseName(it.name).endsWith(".jar", ignoreCase = true) }
                    .toList()
                require(members.isNotEmpty()) { "压缩包里没有找到 JAR 书源（需要 sources_by_xxx.jar）" }
                require(members.size <= MAX_BUNDLE_JARS) { "集合包最多包含 $MAX_BUNDLE_JARS 个 JAR" }
                val seen = mutableSetOf<String>()
                for (member in members) {
                    val base = jarBaseName(member.name)
                    val entry = base.dropLast(4)
                    require(entry.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
                        "集合包里的 $base 需要保留 sources_by_xxx.jar 这样的原名"
                    }
                    require(seen.add(entry)) { "集合包里重复出现 $base" }
                    require(member.size <= MAX_JAR_SIZE) { "$base 超过 20 MB" }
                    val destination = File(directory, "$entry-${UUID.randomUUID()}.jar")
                    try {
                        zip.getInputStream(member).use { input ->
                            writeJarFile(input, destination, MAX_JAR_SIZE, "$base 超过 20 MB")
                        }
                        val imported = runCatching {
                            validateDex(destination)
                            loadPackage(SourcePackage(entry, destination.name))
                        }.getOrElse { error -> throw IllegalArgumentException("$base 导入失败：${error.message}", error) }
                        staged += StagedPackage(entry, destination, imported)
                    } catch (error: Throwable) {
                        destination.delete()
                        throw error
                    }
                }
            }
            val entries = staged.map { it.entry }.toSet()
            val previous = readPackages()
            // 包里要替换掉的旧包先排除，否则「重导同一集合包」会被自己判成 ID 冲突
            val otherIds = _sources.value.filter { it.packageEntry !in entries }.map { it.id }.toSet()
            val bundleIds = mutableSetOf<String>()
            staged.forEach { item ->
                item.sources.forEach { (source, _) ->
                    require(source.id !in otherIds) { "书源 ID ${source.id} 与已导入的其他包重复" }
                    require(bundleIds.add(source.id)) { "集合包里重复出现书源 ID ${source.id}" }
                }
            }
            val updated = previous.filter { it.entry !in entries } + staged.map { SourcePackage(it.entry, it.file.name) }
            persistPackages(updated)
            reload(updated)
            previous.filter { it.entry in entries }.forEach { File(directory, it.file).delete() }
        } catch (error: Throwable) {
            staged.forEach { it.file.delete() }
            throw error
        } finally {
            workspace.deleteRecursively()
        }
    }

    /** 取压缩包内成员的纯文件名；少量打包工具会用反斜杠当分隔符。 */
    private fun jarBaseName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

    /** 写入单个 JAR：先设只读再写（Android 14 动态加载要求文件落盘后不可写）。 */
    private fun writeJarFile(input: java.io.InputStream, destination: File, limit: Long, tooLarge: String) {
        FileOutputStream(destination).use { output ->
            check(destination.setReadOnly()) { "无法设置书源文件的只读属性" }
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= limit) { tooLarge }
                output.write(buffer, 0, count)
            }
        }
    }

    /** 把选中的文件整份复制到缓存目录，用于解压前落地。 */
    private fun copyUri(uri: Uri, destination: File, limit: Long, tooLarge: String) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= limit) { tooLarge }
                    output.write(buffer, 0, count)
                }
            }
        } ?: error("无法读取所选文件")
    }

    private suspend fun importJdr(uri: Uri) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_JAR_SIZE) { "书源包不能超过 20 MB" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("无法读取所选文件")
        val archive = voice.core.extension.engine.JdrArchive.parse(bytes)
        val entry = "jdr:${archive.manifest.id}"
        val otherIds = _sources.value.filter { it.packageEntry != entry }.map { it.id }.toSet()
        require(archive.manifest.sources.none { "jdr:${it.id}" in otherIds }) { "书源 ID 与已导入的其他包重复" }
        val destination = File(directory, "${archive.manifest.id}-${UUID.randomUUID()}.jdr")
        try {
            destination.writeBytes(bytes)
            val previous = readPackages()
            val pkg = SourcePackage(entry, destination.name, previous.find { it.entry == entry }?.enabled ?: true)
            val updated = previous.filter { it.entry != entry } + pkg
            persistPackages(updated)
            reload(updated)
            previous.filter { it.entry == entry }.forEach { File(directory, it.file).delete() }
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    private fun loadPackage(pkg: SourcePackage): List<Pair<ListeningSource, TingShu>> {
        val file = packageFile(pkg)
        require(!file.canWrite()) { "书源文件必须是只读文件，请重新导入" }
        val loader = DexClassLoader(file.absolutePath, context.codeCacheDir.absolutePath, null, context.classLoader)
        val entry = loader.loadClass("com.github.eprendre.${pkg.entry}.SourceEntry")
        val result = entry.getMethod("getSources").invoke(null) as? List<*>
            ?: error("书源入口没有返回源列表")
        require(result.isNotEmpty()) { "书源包没有包含可用书源" }
        val sources = result.map { item ->
            val source = item as? TingShu ?: error("该 JAR 不兼容我的听书接口")
            val id = source.getSourceId()
            require(!id.startsWith("jdr:")) { "JAR 书源不能使用 JDR 保留前缀" }
            require(id.isNotBlank()) { "书源 ID 不能为空" }
            ListeningSource(id, source.getName(), source.getDesc(), pkg.entry) to source
        }
        require(sources.map { it.first.id }.distinct().size == sources.size) { "包内存在重复书源 ID" }
        return sources
    }

    private suspend fun reload(packages: List<SourcePackage>) {
        loaded.clear()
        jdr.clear()
        val sources = mutableListOf<ListeningSource>()
        _packages.value = packages.map { pkg ->
            if (!pkg.enabled) {
                // 停用只卸载运行实例，设置页仍保留包名及内部源的本地快照。
                if (pkg.file.endsWith(".jdr")) {
                    return@map runCatching {
                        val archive = voice.core.extension.engine.JdrArchive.parse(packageFile(pkg).readBytes())
                        pkg.copy(
                            name = archive.manifest.name,
                            sources = archive.manifest.sources.map { ListeningSource("jdr:${it.id}", it.name, archive.manifest.description, pkg.entry, it.capabilities.toSet()) },
                        )
                    }.getOrDefault(pkg)
                }
                if (pkg.sources.isEmpty()) {
                    // 旧版没有保存停用包的名称，只补读一次入口元数据，不注册为可用书源。
                    return@map runCatching {
                        val metadata = loadPackage(pkg).map { it.first }
                        pkg.copy(name = metadata.singleOrNull()?.name ?: pkg.entry, sources = metadata)
                    }.getOrDefault(pkg)
                }
                return@map pkg
            }
            try {
                if (pkg.file.endsWith(".jdr")) {
                    val file = packageFile(pkg)
                    val entries = jdr.load(pkg.entry, file.readBytes())
                    require(entries.none { it.id in loaded || sources.any { existing -> existing.id == it.id } }) { "书源 ID 重复" }
                    sources.addAll(entries)
                    val archive = voice.core.extension.engine.JdrArchive.parse(file.readBytes())
                    return@map pkg.copy(error = null, name = archive.manifest.name, sources = entries)
                }
                val entries = loadPackage(pkg)
                require(entries.none { entry -> entry.first.id in loaded || sources.any { it.id == entry.first.id } }) { "书源 ID 重复" }
                entries.forEach { (metadata, source) ->
                    loaded[metadata.id] = source
                    sources.add(metadata)
                }
                val metadata = entries.map { it.first }
                pkg.copy(error = null, name = metadata.singleOrNull()?.name ?: pkg.entry, sources = metadata)
            } catch (error: LinkageError) {
                pkg.copy(error = "缺少兼容接口：${error.javaClass.simpleName}")
            } catch (error: Exception) {
                pkg.copy(error = error.cause?.message ?: error.message ?: "加载失败")
            }
        }
        _sources.value = sources
        persistPackages(_packages.value)
    }

    private fun readPackages(): List<SourcePackage> {
        val data = JSONArray(prefs.getString("packages", "[]"))
        return List(data.length()) { index ->
            val item = data.getJSONObject(index)
            val entry = item.getString("entry")
            val metadata = item.optJSONArray("sources") ?: JSONArray()
            SourcePackage(
                entry,
                item.getString("file"),
                item.optBoolean("enabled", true),
                name = item.optString("name", ""),
                sources = List(metadata.length()) { sourceIndex ->
                    val source = metadata.getJSONObject(sourceIndex)
                    ListeningSource(source.getString("id"), source.getString("name"), source.optString("description", ""), entry)
                },
            )
        }
    }

    private fun persistPackages(packages: List<SourcePackage>) {
        val data = JSONArray()
        packages.forEach {
            val metadata = JSONArray()
            it.sources.forEach { source ->
                metadata.put(JSONObject().put("id", source.id).put("name", source.name).put("description", source.description))
            }
            data.put(JSONObject().put("entry", it.entry).put("file", it.file).put("enabled", it.enabled).put("name", it.name).put("sources", metadata))
        }
        check(prefs.edit().putString("packages", data.toString()).commit()) { "书源列表保存失败" }
    }

    private fun writeBook(book: ListeningBook) {
        val episodes = JSONArray()
        book.episodes.forEach { episodes.put(JSONObject().put("title", it.title).put("url", it.url)) }
        val data = JSONObject().put("sourceId", book.sourceId).put("url", book.url).put("title", book.title)
            .put("coverUrl", book.coverUrl).put("intro", book.intro).put("episodes", episodes)
            .put("jdrBookExtras", book.jdrBookExtras).put("jdrChapterExtras", JSONObject(book.jdrChapterExtras))
        val file = File(bookDirectory, "${book.key}.json")
        val temporary = File(bookDirectory, "${book.key}.tmp")
        temporary.writeText(data.toString())
        check(temporary.renameTo(file)) { "书籍保存失败" }
    }

    companion object {
        private const val MAX_JAR_SIZE = 20L * 1024 * 1024

        /** 集合包（.zip）本身的大小上限；包里每个 JAR 仍按 20 MB 单独校验 */
        private const val MAX_BUNDLE_SIZE = 64L * 1024 * 1024

        /** 集合包最多带多少个 JAR，避免一次性解出上百个包 */
        private const val MAX_BUNDLE_JARS = 50

        @Volatile
        private var instance: TingshuRepository? = null

        fun get(context: Context): TingshuRepository = instance ?: synchronized(this) {
            instance ?: TingshuRepository(context.applicationContext).also { instance = it }
        }

        internal fun validateDex(file: File) {
            ZipFile(file).use { archive ->
                val dex = requireNotNull(archive.getEntry("classes.dex")) { "JAR 不包含 classes.dex，请先使用 D8 转换" }
                require(dex.size in 8..MAX_JAR_SIZE) { "无效或过大的 DEX 文件" }
                archive.getInputStream(dex).use { input ->
                    val magic = ByteArray(8)
                    require(input.read(magic) == 8 && magic.copyOfRange(0, 4).contentEquals(byteArrayOf(100, 101, 120, 10))) {
                        "JAR 中的 DEX 格式无效"
                    }
                }
            }
        }
    }
}
