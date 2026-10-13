package com.fluxplayer.app.core.data.cloud189

internal object C189SessionErrors {
    private val authenticationErrors = listOf(
        "InvalidAccessToken",
        "InvalidSessionKey",
        "SessionKeyInvalid",
        "UserInvalidOpenToken",
        "userSessionBO is null",
        "require login",
    )

    fun isAuthenticationError(value: String): Boolean = authenticationErrors.any { value.contains(it, ignoreCase = true) }

    fun classify(httpCode: Int, code: String?, message: String, success: Boolean = true): C189ApiException? {
        val authentication = isAuthenticationError(code.orEmpty()) || isAuthenticationError(message)
        return if (httpCode !in 200..299 || code != null || !success || authentication) {
            C189ApiException(code ?: "HTTP $httpCode", message.ifBlank { "天翼云盘请求失败" }, authentication)
        } else {
            null
        }
    }
}

class C189ApiException(val code: String, message: String, val authentication: Boolean) : IllegalStateException("$code: $message")
