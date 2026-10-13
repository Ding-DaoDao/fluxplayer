package com.fluxplayer.app.core.data.security

/**
 * 本机凭证密钥不可用的两种口径。上层**必须区分**：
 *
 * - [PermanentlyInvalid]：Keystore 里的密钥条目再也解不开了（改锁屏密码、系统恢复出厂后
 *   的残留等），该凭证只能让用户重新登录。
 * - [Unavailable]：密钥条目本身完好，只是**此刻**取不到（设备刚开机还没解锁）。
 *   此时**绝不能**删键或清凭证，否则会把用户本来还能读的账号白白作废。
 */
internal sealed class CredentialKeyException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    class PermanentlyInvalid(reason: String, cause: Throwable? = null) :
        CredentialKeyException("本机凭证密钥不可用：$reason", cause)

    class Unavailable(reason: String, cause: Throwable? = null) :
        CredentialKeyException("本机凭证密钥暂时不可用：$reason", cause)
}

/**
 * 凭证加解密。
 *
 * 抽成接口是为了让 [SecureValueCodec] 的迁移/降级逻辑能在 JVM 单元测试里跑
 * （Android Keystore 在本地单测里不可用）。
 */
internal interface CredentialCipher {

    /** 加密并返回可落盘的字符串。 */
    fun encrypt(plain: String, purpose: String): String

    /**
     * 解密 [stored]。
     *
     * @param purpose 字段用途（如 `pan123.password`），作为 GCM 的 AAD 参与认证，
     *   使密文无法在字段之间搬移。
     */
    fun decrypt(stored: String, purpose: String): String
}
