package com.fluxplayer.app.core.data

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object CloudHttpClient {
    val DEFAULT: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .cookieJar(GlobalCookieJar)
        .connectionPool(ConnectionPool(10, 10, TimeUnit.MINUTES))
        .build()

    val NO_REDIRECT: OkHttpClient = DEFAULT.newBuilder()
        .followRedirects(false)
        .build()
}
