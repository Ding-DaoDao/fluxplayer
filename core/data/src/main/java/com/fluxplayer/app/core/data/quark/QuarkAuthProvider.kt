package com.fluxplayer.app.core.data.quark

/**
 * 一套夸克系网盘的播放鉴权状态。
 *
 * 夸克与 UC 现在各持一份（[QuarkAuthProvider] / [UcAuthProvider]），
 * 不再共用同一个全局单例——两家都登录时，播放头不会再互相覆盖。
 */
class QuarkPlaybackAuth(
    defaultReferer: String,
    defaultUserAgent: String,
) {
    @Volatile
    var cookie: String = ""

    @Volatile
    var referer: String = defaultReferer

    @Volatile
    var isActive: Boolean = false

    @Volatile
    var userAgent: String = defaultUserAgent

    fun clear() {
        cookie = ""
        isActive = false
    }

    fun getPlayHeaders(): Map<String, String> = if (isActive && cookie.isNotBlank()) {
        mapOf(
            "Cookie" to cookie,
            "Referer" to referer,
            "User-Agent" to userAgent,
        )
    } else {
        emptyMap()
    }
}

/** 夸克的默认 UA / Referer。 */
internal const val QUARK_PLAY_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch"

/** UC 的默认 UA / Referer。 */
internal const val UC_PLAY_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) uc-cloud-drive/1.6.1 Chrome/100.0.4896.160 Electron/18.3.5.16-b62cf9c50d Safari/537.36"

/** 夸克的播放鉴权（与 UC 完全独立）。 */
@Suppress("PropertyName")
val QuarkAuthProvider: QuarkPlaybackAuth get() = quarkAccounts.current

/** UC 的播放鉴权（与夸克完全独立）。 */
val UcAuthProvider: QuarkPlaybackAuth get() = ucAccounts.current

/** 按来源取对应的鉴权状态；provider 用 "uc" 之外的值一律当作夸克。 */
fun quarkPlaybackAuth(provider: String): QuarkPlaybackAuth =
    if (provider == "uc") UcAuthProvider else QuarkAuthProvider

private val quarkAccounts = com.fluxplayer.app.core.data.cloud.CloudAccountState("quark") { QuarkPlaybackAuth("https://drive.quark.cn/", QUARK_PLAY_UA) }
private val ucAccounts = com.fluxplayer.app.core.data.cloud.CloudAccountState("uc") { QuarkPlaybackAuth("https://drive.uc.cn/", UC_PLAY_UA) }
internal fun clearAllQuarkAccounts(provider: String? = null) {
    if (provider == null || provider == "quark") quarkAccounts.clearAll()
    if (provider == null || provider == "uc") ucAccounts.clearAll()
}
