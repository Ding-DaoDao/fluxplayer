package com.fluxplayer.app.core.data.security

import android.content.SharedPreferences
import android.util.Log

/**
 * 落盘凭证的统一入口。
 *
 * 各云盘的 Cookie / access token / 账号密码原先都是**明文**写 SharedPreferences
 * （云盘凭证泄露等同于账号泄露，123 还明文存了账号密码），这里统一改成
 * Android Keystore 加密后再落盘。用法就是普通的 prefs 读写，只是换成
 * [getSecret] / [putSecret]：
 *
 * ```kotlin
 * prefs.edit()
 *     .putSecret(SCOPE, "passport", passport)
 *     .putSecret(SCOPE, "password", password)
 *     .apply()
 *
 * val password = prefs.getSecret(SCOPE, "password").orEmpty()
 * ```
 *
 * [SCOPE] 用 prefs 文件名（如 `"pan123"`）。它拼进 `purpose`（形如 `pan123.password`）
 * 作为 GCM 的 AAD，使密文无法在字段之间搬移——把 `pan123.password` 的密文贴到
 * `quark.cookie` 上会直接解密失败。
 *
 * **升级兼容**：识别 [SecureValueCodec.PREFIX] 前缀。旧明文照常读出，
 * 并在下次写入时自动变成密文，用户无感、无需重新登录。
 *
 * 六家网盘均使用本入口；天翼旧明文在成功读取后迁移，备份恢复时重新加密。
 */
object CredentialCrypto {

    private const val TAG = "CredentialCrypto"

    private val cipher = AndroidKeystoreCredentialCipher()
    internal fun prepareForRecovery() = cipher.prepareForRecovery()

    private val codec = SecureValueCodec(cipher) { message, error ->
        if (error == null) Log.w(TAG, message) else Log.w(TAG, message, error)
    }

    internal fun decode(stored: String?, purpose: String): String? = codec.decode(stored, purpose)

    internal fun encode(plain: String, purpose: String): String = codec.encode(plain, purpose)

    internal fun purpose(scope: String, key: String): String = "$scope.$key"
}

/**
 * 读取加密凭证项。
 *
 * 值为密文则解密；旧明文原样返回；**解密失败返回 null 而不抛异常、不清数据**
 * （用户会看到登录页，重新登录即可）。
 */
fun SharedPreferences.getSecret(scope: String, key: String): String? =
    CredentialCrypto.decode(getString(key, null), CredentialCrypto.purpose(scope, key))

/** 写入加密凭证项；`value == null` 时移除该项。 */
fun SharedPreferences.Editor.putSecret(
    scope: String,
    key: String,
    value: String?,
): SharedPreferences.Editor =
    putString(key, value?.let { CredentialCrypto.encode(it, CredentialCrypto.purpose(scope, key)) })
