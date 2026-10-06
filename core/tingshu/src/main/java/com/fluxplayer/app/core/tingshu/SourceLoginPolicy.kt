package com.fluxplayer.app.core.tingshu

import voice.core.extension.engine.SourceLogin
import voice.core.extension.engine.SourceSetting

/** 登录凭证需要清空为显式空值，避免配置默认值或旧会话重新生效。 */
internal fun settingsAfterLogout(fields: List<SourceSetting>, values: Map<String, String>): Map<String, String> =
    fields.filter { it.type != "button" }.associate { field ->
        val key = field.key.lowercase().replace("_", "").replace("-", "")
        val credential = field.type == "password" ||
            listOf("password", "cookie", "token", "authorization", "sessionkey", "sessionid", "secret", "credential").any { key.contains(it) } ||
            key in setOf("account", "username", "user", "passport", "phone", "phonenumber", "mobile", "email", "loginphone") ||
            listOf("账号", "帐号", "手机号", "密码").any { field.label.contains(it) }
        field.key to if (credential) "" else values[field.key].orEmpty()
    }

/** 旧网盘源登录后会省略网页登录地址，宿主保留其已声明的登录方式。 */
internal fun netdiskWebLogin(sourceId: String, login: SourceLogin): SourceLogin {
    val url = when (sourceId) {
        "quark" -> "https://pan.quark.cn/"
        "yun139" -> "https://yun.139.com/m/#/login"
        "cloud189" -> "https://cloud.189.cn/web/login.html"
        else -> return login
    }
    return login.copy(
        webUrl = login.webUrl.ifBlank { url },
        cookieUrl = login.cookieUrl.ifBlank { url },
        desktopUserAgent = true,
    )
}
