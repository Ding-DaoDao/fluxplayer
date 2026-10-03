package com.github.eprendre.tingshu.extensions

import com.fluxplayer.app.core.tingshu.SourceHost
import com.github.eprendre.tingshu.utils.Book
import io.reactivex.disposables.Disposable
import io.reactivex.disposables.Disposables
import java.io.File
import java.net.URL
import java.net.URLDecoder
import org.jsoup.Connection

fun splitQuery(url: URL): LinkedHashMap<String, String> {
    val result = linkedMapOf<String, String>()
    url.query.orEmpty().split("&").filter { it.isNotBlank() }.forEach {
        result[URLDecoder.decode(it.substringBefore('='), "UTF-8")] =
            URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
    }
    return result
}

fun Connection.config(isDesktop: Boolean = false): Connection =
    userAgent(if (isDesktop) getDesktopUA() else getMobileUA()).timeout(20_000).apply {
        getCookie(request().url().toString())?.let { header("Cookie", it) }
    }

fun getDesktopUA(): String = SourceHost.DESKTOP_UA

fun getMobileUA(): String = SourceHost.MOBILE_UA

fun getCookie(url: String): String? = SourceHost.cookie(url)

fun showToast(msg: String) = SourceHost.toast(msg)

fun getCurrentBook(): Book? = SourceHost.currentBook.get()

fun notifyLoadingEpisodes(pageInfo: String?) = Unit

/** 宿主已在后台串行调用解析器，嵌套解析保持相同调用上下文。 */
fun extractorAsyncExecute(
    url: String,
    autoPlay: Boolean,
    isCache: Boolean,
    isDebug: Boolean,
    backgroundTask: () -> String,
    mainThreadCallback: (String) -> Unit,
): Disposable {
    mainThreadCallback(backgroundTask())
    return Disposables.disposed()
}

fun todayPlaybackTime(): Long = SourceHost.getString("host.playbackTime", "0")?.toLongOrNull() ?: 0L

fun playerControl(action: String) {
    throw UnsupportedOperationException("该书源需要尚未支持的播放控制接口：$action")
}

fun getSourceCacheDir(sourceId: String, subDir: String): File = SourceHost.cacheDir(sourceId, subDir)
