package com.fluxplayer.app.core.data.cloud

import android.content.Context
import com.fluxplayer.app.core.data.security.getSecret
import org.json.JSONObject

/** 与 wenxi 的登录信息视图一致，只提供可复用的令牌，不导出登录密码和设备私钥。 */
data class CloudAccountLoginInfo(val fields: List<Field>, val preferred: Field?) {
    data class Field(val key: String, val label: String, val value: String)
    val json: String get() = JSONObject(fields.associate { it.key to it.value }).toString(2)
    companion object {
        fun read(context: Context, provider: String, account: String): CloudAccountLoginInfo {
            check(CloudAccountStore.contains(context, provider, account)) { "账号已移除，请重新打开账号菜单" }
            val prefs = CloudAccountStore.accountPreferences(context, provider, account)
            fun value(vararg aliases: String): String = aliases.firstNotNullOfOrNull { key -> prefs.getSecret(provider, key)?.takeIf { it.isNotBlank() } }.orEmpty()
            val fields = mutableListOf<Field>()
            fun add(key: String, label: String, value: String) {
                if (value.isNotBlank()) fields.add(Field(key, label, value))
            }
            add("cookie", "Cookie", value("cookie", "cookies"))
            add("accessToken", "访问令牌", value("accessToken", "token"))
            add("refreshToken", "刷新令牌", value("refreshToken", "refresh_token"))
            add("authorization", "访问授权", value("authorization"))
            val preferred = (listOf("cookie") + if (provider == "alipan") listOf("refreshToken", "accessToken", "authorization") else listOf("accessToken", "refreshToken", "authorization"))
                .firstNotNullOfOrNull { key -> fields.firstOrNull { it.key == key } }
            if (preferred != null) {
                add("userId", "用户标识", value("user_id", "userId", "userDomainId"))
                if (provider != "alipan") {
                    add("deviceId", "设备标识", value("device_id", "deviceId"))
                    add("uuid", "设备编号", value("login_uuid", "uuid"))
                }
            }
            return CloudAccountLoginInfo(fields, preferred)
        }
    }
}
