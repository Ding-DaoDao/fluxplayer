package com.fluxplayer.app.core.data.security

/**
 * 落盘凭证的编码格式：`enc:v1:<密文>`。
 *
 * 加前缀是为了**兼容旧数据**：升级前存的是明文，读到时原样返回、下一次写入时自动升级成密文，
 * 用户不需要重新登录。判断是不是密文只看前缀，不需要尝试解密。
 */
internal class SecureValueCodec(
    private val cipher: CredentialCipher,
    private val onFailure: (String, Throwable?) -> Unit = { _, _ -> },
) {

    fun isEncrypted(stored: String?): Boolean = stored != null && stored.startsWith(PREFIX)

    fun encode(plain: String, purpose: String): String = PREFIX + cipher.encrypt(plain, purpose)

    /**
     * 解密；**任何失败都返回 null，绝不抛异常**，也**绝不清除已存数据**。
     *
     * 这条是刻意的：凭证读不出来的后果应该是「显示登录页、让用户重新登录」，
     * 而不是把 App 打崩，或把用户下次解锁后本来还能读的凭证提前删掉。
     *
     * @param stored 原始落盘值；null 表示没有该项
     * @param purpose 字段用途，参与 GCM 认证
     */
    fun decode(stored: String?, purpose: String): String? {
        if (stored == null) return null
        // 旧明文（升级前写入）：直接用，等下次写入自动变密文
        if (!isEncrypted(stored)) return stored
        val payload = stored.substring(PREFIX.length)
        return try {
            cipher.decrypt(payload, purpose)
        } catch (error: CredentialKeyException.Unavailable) {
            // 只是此刻取不到密钥（如设备未解锁）：保持数据不动，下次再试
            onFailure("$purpose：本机密钥暂时不可用，本次读不到该项（已存凭证未改动）", error)
            null
        } catch (error: CredentialKeyException.PermanentlyInvalid) {
            onFailure("$purpose：本机密钥已永久失效，该项需要重新登录", error)
            null
        } catch (error: Exception) {
            onFailure("$purpose：解密失败（密文损坏或字段不符），该项需要重新登录", error)
            null
        }
    }

    companion object {
        const val PREFIX = "enc:v1:"
    }
}
