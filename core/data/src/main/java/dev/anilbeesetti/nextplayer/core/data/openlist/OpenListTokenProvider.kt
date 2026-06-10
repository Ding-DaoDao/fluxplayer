package dev.anilbeesetti.nextplayer.core.data.openlist

/**
 * 线程安全的 OpenList Bearer token 单例持有者。
 *
 * OpenListBrowserViewModel 登录成功后设置 token，
 * AuthAwareDataSourceFactory 在 HTTP 请求时读取并注入 Authorization header。
 */
object OpenListTokenProvider {
    @Volatile
    var bearerToken: String? = null
}
