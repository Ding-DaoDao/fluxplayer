package com.fluxplayer.app.core.data.aliyun

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator

/** 阿里设备会话需要可恢复的 secp256k1 签名，密钥由本机随机生成。 */
internal object AliyunDeviceSignature {
    private const val APP_ID = "5dde4e1bdf9e4966b387ba58f4b3fdc3"
    private val curve = SECNamedCurves.getByName("secp256k1")
    private val domain = ECDomainParameters(curve.curve, curve.g, curve.n, curve.h)

    fun privateKey(): String {
        var value: BigInteger
        do {
            value = BigInteger(256, SecureRandom())
        } while (value.signum() <= 0 || value >= curve.n)
        return value.toString(16).padStart(64, '0')
    }

    fun sign(privateKey: String, deviceId: String, userId: String): Pair<String, String> {
        val number = BigInteger(privateKey, 16)
        require(number.signum() > 0 && number < curve.n) { "阿里设备密钥无效" }
        val digest = MessageDigest.getInstance("SHA-256").digest("$APP_ID:$deviceId:$userId:0".toByteArray())
        val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest())).apply { init(true, ECPrivateKeyParameters(number, domain)) }
        val values = signer.generateSignature(digest)
        val r = values[0]
        val s = if (values[1] > curve.n.shiftRight(1)) curve.n.subtract(values[1]) else values[1]
        val publicKey = curve.g.multiply(number).normalize()
        val inverse = s.modInverse(curve.n)
        val point = curve.g.multiply(BigInteger(1, digest).multiply(inverse).mod(curve.n))
            .add(publicKey.multiply(r.multiply(inverse).mod(curve.n))).normalize()
        val recovery = (if (point.affineYCoord.toBigInteger().testBit(0)) 1 else 0) or (if (point.affineXCoord.toBigInteger() >= curve.n) 2 else 0)
        val signature = r.toString(16).padStart(64, '0') + s.toString(16).padStart(64, '0') + recovery.toString(16).padStart(2, '0')
        return signature to publicKey.getEncoded(false).joinToString("") { "%02x".format(it) }
    }
}
