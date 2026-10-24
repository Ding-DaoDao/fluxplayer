package com.fluxplayer.app.core.data.security

import com.fluxplayer.app.core.common.CloudAccountIdentity
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** 将自动 Cookie 写回绑定到客户端所属会话，旧响应不能污染切换后的账号。 */
internal class CloudSessionCookieJar(private val provider: String, private val delegate: CookieJar) : CookieJar {
    private val revision = CloudSessionFence.current(provider)

    private val account = CloudAccountIdentity.active(provider)
    override fun loadForRequest(url: HttpUrl): List<Cookie> = CloudAccountIdentity.runAs(provider, account) {
        CloudSessionFence.checkCurrent(provider, revision)
        delegate.loadForRequest(url)
    }
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        CloudAccountIdentity.runAs(provider, account) {
            CloudSessionFence.commit(provider, revision) { delegate.saveFromResponse(url, cookies) }
        }
    }
}
