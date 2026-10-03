package com.github.eprendre.tingshu.sources

import com.fluxplayer.app.core.tingshu.SourceHost
import com.github.eprendre.tingshu.extensions.config
import com.github.kittinunf.fuel.Fuel
import com.github.kittinunf.fuel.json.FuelJson
import com.github.kittinunf.fuel.json.responseJson
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

object AudioUrlDirectExtractor : AudioUrlExtractor {
    override fun extract(url: String, autoPlay: Boolean, isCache: Boolean, isDebug: Boolean) = SourceHost.publish(url)
}

object AudioUrlCustomExtractor : AudioUrlExtractor {
    private val parser = ThreadLocal<(String) -> String>()

    fun setUp(parse: (String) -> String) = parser.set(parse)

    override fun extract(url: String, autoPlay: Boolean, isCache: Boolean, isDebug: Boolean) {
        SourceHost.publish(checkNotNull(parser.get()) { "书源没有设置解析器" }(url))
    }
}

object AudioUrlJsoupExtractor : AudioUrlExtractor {
    private val parser = ThreadLocal<(Document) -> String>()
    private val desktop = ThreadLocal<Boolean>()

    @JvmOverloads
    fun setUp(isDesktop: Boolean = false, parse: (Document) -> String) {
        desktop.set(isDesktop)
        parser.set(parse)
    }

    override fun extract(url: String, autoPlay: Boolean, isCache: Boolean, isDebug: Boolean) {
        val document = Jsoup.connect(url).config(desktop.get() == true).get()
        SourceHost.publish(checkNotNull(parser.get()) { "书源没有设置解析器" }(document))
    }
}

object AudioUrlJsonExtractor : AudioUrlExtractor {
    private val parser = ThreadLocal<(FuelJson) -> String>()
    private val desktop = ThreadLocal<Boolean>()

    @JvmOverloads
    fun setUp(isDesktop: Boolean = false, parse: (FuelJson) -> String) {
        desktop.set(isDesktop)
        parser.set(parse)
    }

    override fun extract(url: String, autoPlay: Boolean, isCache: Boolean, isDebug: Boolean) {
        val (_, _, result) = Fuel.get(url).header(
            "User-Agent" to if (desktop.get() == true) SourceHost.DESKTOP_UA else SourceHost.MOBILE_UA,
        ).timeout(20_000).timeoutRead(20_000).responseJson()
        SourceHost.publish(checkNotNull(parser.get()) { "书源没有设置解析器" }(result.get()))
    }
}
