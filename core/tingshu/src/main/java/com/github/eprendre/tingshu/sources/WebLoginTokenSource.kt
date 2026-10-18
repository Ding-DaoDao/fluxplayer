package com.github.eprendre.tingshu.sources

/** 阿里云盘网页登录的 localStorage 凭证，由宿主登录窗口回传后交给书源验证。 */
interface WebLoginTokenSource : ILogin {
    fun onWebLoginToken(tokenJson: String)

    fun isWebLoggedIn(): Boolean
}
