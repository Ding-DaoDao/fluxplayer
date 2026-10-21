package com.fluxplayer.app.feature.tingshu

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fluxplayer.app.core.tingshu.JdrFolder
import com.fluxplayer.app.core.tingshu.ListeningBook
import com.fluxplayer.app.core.tingshu.ListeningErrors
import com.fluxplayer.app.core.tingshu.ListeningSource
import com.fluxplayer.app.core.tingshu.TingshuRepository
import com.github.eprendre.tingshu.utils.Book
import com.github.eprendre.tingshu.utils.CategoryMenu
import com.github.eprendre.tingshu.utils.ConfigItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 书源列表的排序方式。
 *
 * [DEFAULT] 完全沿用源返回的顺序——jar 源可以在自己的配置里排好序，
 * jdr 源按接口顺序返回，选中默认时不做任何二次排序。
 *
 * 维度对齐网盘网页版的排序菜单：名称 / 修改时间 / 大小，各给双向。
 */
enum class BookSortMode(val label: String) {
    DEFAULT("默认顺序"),
    NAME_ASC("名称 A-Z"),
    NAME_DESC("名称 Z-A"),
    NEWEST("最近更新"),
    OLDEST("最早更新"),
    LARGEST("大小 大→小"),
    SMALLEST("大小 小→大"),
}

/** 书源列表的展示样式。 */
enum class BookViewMode { GRID, LIST }

data class SourceBrowseState(
    val source: ListeningSource? = null,
    val menus: List<CategoryMenu> = emptyList(),
    val books: List<Book> = emptyList(),
    val folders: List<JdrFolder> = emptyList(),
    val shortcuts: List<JdrFolder> = emptyList(),
    val directoryId: String? = null,
    val browseNextPage: Int? = null,
    val detail: ListeningBook? = null,
    /** 播放确认弹窗预解析出的详情，就绪后可一键起播 */
    val pendingDetail: ListeningBook? = null,
    val pendingBookUrl: String? = null,
    val pendingCoverRevision: Long = 0,
    val configMessage: String? = null,
    val title: String = "",
    val searchKeyword: String? = null,
    val page: Int = 0,
    val totalPages: Int = 0,
    val nextUrl: String = "",
    /** 当前分类的请求地址，下拉刷新时用它重新拉取 */
    val currentCategoryUrl: String = "",
    val loading: Boolean = false,
    val resolvingBook: Boolean = false,
    /** 下拉刷新中（列表保持显示，不闪空） */
    val refreshing: Boolean = false,
    /** 触底自动加载下一页中 */
    val loadingMore: Boolean = false,
    val error: String? = null,
    val configItems: List<ConfigItem>? = null,
    val configRevision: Int = 0,
    /** 配置对话框里展示的登录态；null = 该源不在已知网盘之列，状态未知 */
    val loginState: Boolean? = null,
    val webLoginAvailable: Boolean = false,
    val canGoBack: Boolean = false,
    /** 待启动的书源 WebView 登录页，UI 侧消费后调SourceLoginActivity 并清空 */
    val pendingLogin: PendingLogin? = null,
    /** 列表排序方式（App 侧本地排序，切换即时生效，不需要重新请求） */
    val sortMode: BookSortMode = BookSortMode.DEFAULT,
    /** 网格 / 列表展示 */
    val viewMode: BookViewMode = BookViewMode.GRID,
)

/** 书源 WebView 登录所需的全部信息，由 SourceHost.loginInfo() 提供 */
data class PendingLogin(
    val sourceId: String,
    val sourceName: String,
    val url: String,
    val userAgent: String,
)

internal val ListeningSource.isCloudLibrary: Boolean
    get() = "browse" in capabilities || packageEntry in setOf("sources_by_pan123", "sources_by_quark", "sources_by_cloud189", "sources_by_yun139")

class TingshuViewModel(application: Application) : AndroidViewModel(application) {
    val repository = TingshuRepository.get(application)

    /** 列表展示偏好（网格/列表、排序规则）：跨会话记住，换源也保持。 */
    private val listPreferences = application.getSharedPreferences(PREF_SOURCE_LIST, Context.MODE_PRIVATE)

    private val mutableState = MutableStateFlow(freshState())
    val state = mutableState.asStateFlow()
    private val history = mutableListOf<SourceBrowseState>()
    private var loadJob: Job? = null
    private var retryAction: (suspend () -> Unit)? = null

    /**
     * 重建 state 时一律带上已保存的展示偏好。
     * 换源、重进、从设置页返回都会整块重建 state，不带上的话用户选的网格/排序会被重置。
     */
    private fun freshState(
        source: ListeningSource? = null,
        title: String = "",
        loading: Boolean = false,
    ): SourceBrowseState = SourceBrowseState(
        source = source,
        title = title,
        loading = loading,
        sortMode = storedSortMode(),
        viewMode = storedViewMode(),
    )

    private fun storedSortMode(): BookSortMode =
        listPreferences.getString(KEY_SORT_MODE, null)
            ?.let { name -> BookSortMode.values().firstOrNull { it.name == name } }
            ?: BookSortMode.DEFAULT

    private fun storedViewMode(): BookViewMode =
        listPreferences.getString(KEY_VIEW_MODE, null)
            ?.let { name -> BookViewMode.values().firstOrNull { it.name == name } }
            ?: BookViewMode.GRID

    fun importSource(uri: Uri) = operation { repository.importSource(uri) }

    fun enable(entry: String, enabled: Boolean) = operation { repository.setEnabled(entry, enabled) }

    fun remove(entry: String) = operation { repository.remove(entry) }

    fun open(source: ListeningSource) {
        history.clear()
        mutableState.value = freshState(source = source, title = source.name)
        operation {
            if ("browse" in source.capabilities) {
                // 带着已保存的排序进源，第一次拉取就由服务端排好，翻页顺序也就对着
                val mode = storedSortMode()
                val initialDirectory = if (mode == BookSortMode.DEFAULT) null else encodeSortId(mode, null)
                val page = repository.browseJdr(source.id, initialDirectory)
                mutableState.update { it.copy(books = page.books, folders = page.folders, shortcuts = page.shortcuts, directoryId = page.directoryId, browseNextPage = page.nextPage, page = 1) }
                return@operation
            }
            val menus = repository.menus(source.id)
            val tabs = menus.flatMap { it.tabs }
            // 云盘源的单入口代表配置的根目录，直接加载，不把隐藏入口显示为书籍。
            if (source.isCloudLibrary && tabs.size == 1) {
                val category = repository.category(source.id, tabs.single().url)
                mutableState.update {
                    it.copy(
                        books = category.list,
                        page = category.currentPage,
                        totalPages = category.totalPage,
                        nextUrl = category.nextUrl,
                        currentCategoryUrl = tabs.single().url,
                    )
                }
            } else {
                mutableState.update { it.copy(menus = menus.filter { menu -> menu.tabs.isNotEmpty() }) }
            }
        }
    }

    fun browseDirectory(id: String, name: String, append: Boolean = false, shortcut: Boolean = false) {
        val source = state.value.source ?: return
        val previous = state.value
        val pageNumber = if (append) previous.browseNextPage ?: return else 1
        operation {
            val page = repository.browseJdr(source.id, id, pageNumber)
            if (shortcut) history.clear() else if (!append) history.add(previous.copy(loading = false, error = null))
            mutableState.update {
                it.copy(
                    books = if (append) (it.books + page.books).distinctBy { book -> book.bookUrl } else page.books,
                    folders = if (append) (it.folders + page.folders).distinctBy { folder -> folder.id } else page.folders,
                    shortcuts = page.shortcuts,
                    searchKeyword = null,
                    totalPages = 0,
                    nextUrl = "",
                    directoryId = page.directoryId,
                    browseNextPage = page.nextPage,
                    page = pageNumber,
                    menus = emptyList(),
                    title = name,
                    canGoBack = history.isNotEmpty(),
                )
            }
        }
    }

    /**
     * 切换列表排序。
     *
     * 先在本地立即重排（不闪、不等待网络），再让源去服务端重拉一次——
     * 服务端排序的意义是**翻页也带同一条规则**，只有这样才能保证跨页顺序正确。
     */
    fun setSortMode(mode: BookSortMode) {
        listPreferences.edit().putString(KEY_SORT_MODE, mode.name).apply()
        val current = mutableState.value
        mutableState.update { it.copy(sortMode = mode) }

        val source = current.source ?: return
        // 搜索结果是 search() 一次性拿回来的，没有 directoryId 通道可以带排序参数；
        // 这时候重新 browse 会把搜索结果换成目录列表，所以只做本地重排。
        if (current.searchKeyword != null) return
        if ("browse" !in source.capabilities || current.books.isEmpty()) return

        val directory = encodeSortId(mode, current.directoryId)
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val page = repository.browseJdr(source.id, directory, 1)
                mutableState.update {
                    it.copy(
                        books = page.books,
                        folders = page.folders,
                        shortcuts = page.shortcuts,
                        directoryId = page.directoryId,
                        browseNextPage = page.nextPage,
                        page = 1,
                    )
                }
            } catch (error: Throwable) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                // 本地排序已经生效，服务端这次没拿到就只提示，不动列表
                mutableState.update { it.copy(error = ListeningErrors.describe(error, "排序失败")) }
            }
        }
    }

    /** 网格 / 列表来回切；选择会记住。 */
    fun toggleViewMode() {
        val next = if (mutableState.value.viewMode == BookViewMode.GRID) BookViewMode.LIST else BookViewMode.GRID
        listPreferences.edit().putString(KEY_VIEW_MODE, next.name).apply()
        mutableState.update { it.copy(viewMode = next) }
    }

    /**
     * App 的排序 → 源能理解的 directoryId。
     *
     * 格式 `sort:<field>:<order>~<真实目录 id>`：源从里面拆出目录和排序，
     * 转成网盘自己的 orderBy 参数。DEFAULT 返回纯目录，让源按默认序拉。
     */
    private fun encodeSortId(mode: BookSortMode, directoryId: String?): String {
        val realDirectory = directoryId?.substringAfter('~', directoryId) ?: ""
        val spec = when (mode) {
            BookSortMode.NAME_ASC -> "name:asc"
            BookSortMode.NAME_DESC -> "name:desc"
            BookSortMode.NEWEST -> "time:desc"
            BookSortMode.OLDEST -> "time:asc"
            BookSortMode.LARGEST -> "size:desc"
            BookSortMode.SMALLEST -> "size:asc"
            BookSortMode.DEFAULT -> return realDirectory
        }
        return "sort:$spec~$realDirectory"
    }

    fun category(url: String, title: String, append: Boolean = false) {
        val source = state.value.source ?: return
        val previous = state.value
        operation {
            val category = repository.category(source.id, url)
            if (!append) history.add(previous.copy(loading = false, error = null))
            mutableState.update {
                it.copy(
                    books = if (append) it.books + category.list else category.list,
                    menus = emptyList(),
                    title = title,
                    page = category.currentPage,
                    totalPages = category.totalPage,
                    nextUrl = category.nextUrl,
                    currentCategoryUrl = url,
                    canGoBack = history.isNotEmpty(),
                )
            }
        }
    }

    fun search(keyword: String, append: Boolean = false) {
        val query = keyword.trim().takeIf { it.isNotEmpty() } ?: return
        val previous = state.value
        val source = previous.source ?: return
        val page = if (append) previous.page + 1 else 1
        if (append) mutableState.update { it.copy(loadingMore = true) }
        operation {
            val (books, total) = repository.search(source.id, query, page)
            if (!append && previous.searchKeyword == null) history.add(previous.copy(loading = false, error = null))
            mutableState.update {
                it.copy(
                    books = if (append) (it.books + books).distinctBy { book -> book.bookUrl } else books,
                    folders = emptyList(), menus = emptyList(), directoryId = null, browseNextPage = null,
                    nextUrl = "", searchKeyword = query, page = page, totalPages = total,
                    title = "搜索：$query", canGoBack = history.isNotEmpty(),
                )
            }
        }
    }

    fun nextPage() {
        val state = state.value
        if (state.loading || state.loadingMore) return
        if (!hasMore(state)) return
        if (state.searchKeyword != null) {
            search(state.searchKeyword, true)
        } else if (state.directoryId != null && state.browseNextPage != null) {
            browseDirectory(state.directoryId, state.title, true)
        } else if (state.nextUrl.isNotBlank()) {
            category(state.nextUrl, state.title, true)
        }
    }

    /** 是否还有下一页：网盘看 browseNextPage，分类看 nextUrl。 */
    private fun hasMore(state: SourceBrowseState): Boolean =
        state.browseNextPage != null || state.nextUrl.isNotBlank() || (state.searchKeyword != null && state.page < state.totalPages)

    /** 供列表触底回调：返回是否真的发起了加载（已到末页或正在加载时为 false）。 */
    fun loadMoreIfNeeded(): Boolean {
        val state = state.value
        if (state.loading || state.loadingMore || state.error != null || state.pendingBookUrl != null || !hasMore(state)) return false
        nextPage()
        return true
    }

    /**
     * 下拉刷新：重载当前视图。
     * 书库首页 → 重新加载书源入口；分类子页 → 重新拉当前页（列表保持显示，不闪空）。
     */
    fun refresh() {
        val state = state.value
        val source = repository.sources.value.firstOrNull { it.id == state.source?.id } ?: state.source ?: return
        mutableState.update { it.copy(source = source) }
        if (state.refreshing) return
        mutableState.update { it.copy(refreshing = true, error = null) }
        if (state.searchKeyword != null) {
            search(state.searchKeyword)
            return
        }
        operation {
            if ("browse" in source.capabilities) {
                // 直接沿用当前 directoryId：它可能是 sort:*~<目录>，丢掉就等于把排序重置了
                val page = repository.browseJdr(source.id, state.directoryId)
                mutableState.update { it.copy(books = page.books, folders = page.folders, shortcuts = page.shortcuts, directoryId = page.directoryId, browseNextPage = page.nextPage, page = 1) }
                return@operation
            }
            val refreshed = when {
                state.canGoBack -> repository.category(source.id, state.currentCategoryUrl).let { it.list to it.totalPage }
                else -> {
                    val menus = repository.menus(source.id)
                    val tabs = menus.flatMap { it.tabs }
                    if (source.isCloudLibrary && tabs.size == 1) {
                        val category = repository.category(source.id, tabs.single().url)
                        category.list to category.totalPage
                    } else {
                        mutableState.update { it.copy(menus = menus.filter { menu -> menu.tabs.isNotEmpty() }) }
                        return@operation
                    }
                }
            }
            mutableState.update {
                it.copy(
                    books = refreshed.first,
                    page = 1,
                    totalPages = refreshed.second,
                    nextUrl = if (state.canGoBack) it.nextUrl else "",
                    menus = if (state.canGoBack) it.menus else emptyList(),
                    canGoBack = state.canGoBack,
                )
            }
        }
    }

    /** 进入书籍详情页（保留给测试与未来可能的"查看详情"入口；列表点击已改走 [resolveForPlayback]）。 */
    fun detail(book: Book) {
        val source = state.value.source ?: return
        operation {
            val detail = repository.detail(source.id, book)
            mutableState.update { it.copy(detail = detail) }
        }
    }

    /**
     * 解析书籍详情并直接交给播放页，跳过详情页。
     * 不写入 state.detail —— 否则列表页会短暂切到详情页再被播放页盖住。
     * 解析失败时错误落在 state.error，由列表页的错误条展示。
     * [onReady] 在 viewModelScope（主线程）回调，可安全启动 Activity。
     */
    fun resolveForPlayback(book: Book, onReady: (ListeningBook) -> Unit) {
        val source = state.value.source ?: return
        operation {
            val detail = repository.detail(source.id, book)
            onReady(detail)
        }
    }

    /**
     * 解析书籍详情用于填充播放确认弹窗（章节数等），不启动播放。
     * 先用持久化详情填充弹窗，再从书源更新目录；用户确认后才起播。
     */
    fun resolvePending(book: Book, refresh: Boolean = false) {
        val source = state.value.source ?: return
        val sameBook = state.value.pendingBookUrl == book.bookUrl
        mutableState.update {
            it.copy(
                pendingDetail = if (sameBook) it.pendingDetail else null,
                pendingBookUrl = book.bookUrl,
                pendingCoverRevision = if (refresh) System.nanoTime() else if (sameBook) it.pendingCoverRevision else 0,
            )
        }
        operation(resolvingBook = true) {
            if (!refresh) {
                repository.cachedBook(source.id, book.bookUrl)?.let { cached ->
                    mutableState.update { it.copy(pendingDetail = cached) }
                }
            }
            val detail = repository.detail(source.id, book, refreshMetadata = refresh)
            mutableState.update { current ->
                current.copy(
                    pendingDetail = detail,
                    books = current.books.map { listed ->
                        if (listed.bookUrl != detail.url) {
                            listed
                        } else {
                            Book(detail.coverUrl, detail.url, detail.title, listed.author, detail.intro).apply {
                                sourceId = source.id
                                jdrExtras = detail.jdrBookExtras
                            }
                        }
                    },
                )
            }
        }
    }

    fun consumePendingDetail() {
        if (state.value.pendingBookUrl != null) loadJob?.cancel()
        mutableState.update { it.copy(pendingDetail = null, pendingBookUrl = null, pendingCoverRevision = 0, resolvingBook = false, error = null) }
    }

    fun openSavedBook(key: String) = operation {
        history.clear()
        mutableState.value = freshState(loading = true)
        val book = repository.book(key)
        mutableState.update { it.copy(detail = book) }
    }

    /**
     * 最近收听书籍直达播放页：解析出详情后立即回调，跳过详情页。
     * 只置 loading 标记，不重建 state —— 避免把 source 清空导致后续操作失效。
     */
    fun playSavedBook(key: String, onReady: (ListeningBook) -> Unit) = operation {
        history.clear()
        mutableState.update { it.copy(loading = true, error = null) }
        val book = repository.book(key)
        onReady(book)
    }

    fun configure(source: ListeningSource) {
        mutableState.value = freshState(source = source)
        configure()
    }

    fun configure() {
        val source = state.value.source ?: return
        operation {
            val items = repository.config(source.id)
            mutableState.update { it.copy(configItems = items, loginState = repository.isLoggedIn(source.id), webLoginAvailable = repository.loginInfo(source.id) != null) }
        }
    }

    fun saveConfig(values: Map<String, String>) {
        val source = state.value.source ?: return
        operation {
            repository.saveConfig(source.id, values)
            mutableState.update { it.copy(configItems = null, loginState = null) }
        }
    }

    fun configAction(action: () -> Unit, values: Map<String, String>) = operation {
        val source = state.value.source ?: return@operation
        repository.saveConfig(source.id, values)
        val message = repository.configAction(action)
        val items = repository.config(source.id)
        // 「清除登录痕迹」等动作会改动登录态，一并刷新状态显示
        mutableState.update {
            it.copy(configItems = items, configRevision = it.configRevision + 1, loginState = repository.isLoggedIn(source.id), configMessage = message)
        }
    }

    /** 登录页返回后重新探测登录态 */
    fun refreshLoginState() = operation {
        val source = state.value.source ?: return@operation
        mutableState.update { it.copy(configItems = repository.config(source.id), configRevision = it.configRevision + 1, loginState = repository.isLoggedIn(source.id)) }
    }

    fun completeWebLogin(sourceId: String, tokenJson: String) {
        if (state.value.source?.id != sourceId) return
        operation {
            repository.completeWebLogin(sourceId, tokenJson)
            mutableState.update {
                it.copy(configItems = repository.config(sourceId), configRevision = it.configRevision + 1, loginState = repository.isLoggedIn(sourceId), configMessage = "阿里云盘登录成功，凭证已自动保存")
            }
        }
    }

    fun dismissConfig() = mutableState.update { it.copy(configItems = null, loginState = null, configMessage = null) }

    /**
     * 打开书源 WebView 登录页。
     *
     * 天翼/夸克/移动等网盘源实现了 ILogin，登录页地址由书源提供；登录成功后 Cookie
     * 由 WebView 写入系统 CookieManager，书源随后自行读取，无需在此校验结果。
     * JDR 源有各自的登录实现，不走这条路径。
     *
     * 登录信息写入 [TingshuUiState.pendingLogin]，由 UI 侧消费后启动登录页。
     */
    fun startWebLogin(source: ListeningSource) {
        if (source.id.startsWith("jdr:")) return
        operation {
            val info = repository.loginInfo(source.id)
            if (info == null) {
                mutableState.update { it.copy(error = "该书源未提供 WebView 登录页") }
            } else {
                mutableState.update { it.copy(pendingLogin = PendingLogin(source.id, source.name, info.url, info.userAgent)) }
            }
        }
    }

    fun consumePendingLogin() = mutableState.update { it.copy(pendingLogin = null) }

    fun dismissError() = mutableState.update { it.copy(error = null) }

    fun retry() {
        retryAction?.let { operation(resolvingBook = state.value.pendingBookUrl != null, action = it) }
    }

    fun back() {
        loadJob?.cancel()
        when {
            state.value.detail != null -> mutableState.update { it.copy(detail = null, loading = false, error = null) }
            history.isNotEmpty() -> mutableState.value = history.removeAt(history.lastIndex)
            else -> mutableState.value = freshState()
        }
    }

    private fun operation(resolvingBook: Boolean = false, action: suspend () -> Unit) {
        loadJob?.cancel()
        retryAction = action
        loadJob = viewModelScope.launch {
            mutableState.update { it.copy(loading = !resolvingBook, resolvingBook = resolvingBook, error = null) }
            try {
                action()
                mutableState.update { it.copy(loading = false, resolvingBook = false, loadingMore = false, refreshing = false) }
            } catch (error: Throwable) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                mutableState.update {
                    it.copy(
                        loading = false,
                        resolvingBook = false,
                        loadingMore = false,
                        refreshing = false,
                        error = ListeningErrors.describe(error, if (it.pendingBookUrl != null) "章节加载失败" else "书源操作失败"),
                    )
                }
            }
        }
    }

    private companion object {
        const val PREF_SOURCE_LIST = "tingshu_source_list"
        const val KEY_SORT_MODE = "sort_mode"
        const val KEY_VIEW_MODE = "view_mode"
    }
}
