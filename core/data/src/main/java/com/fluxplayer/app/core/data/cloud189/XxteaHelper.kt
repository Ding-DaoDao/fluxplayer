package com.fluxplayer.app.core.data.cloud189

import android.util.Log

/**
 * XXTEA 解密工具 —— 用于解密天翼云盘 SMS 登录返回的 paras 字段
 *
 * 算法来源于海阔视界 csdown.js 中的 b_II_I / a_BZ_I / a_IZ_B / utf8ArrayToStr
 * 固定密钥(Hex):
 *   67377150343554566b51354736694e6262686155356e586c41656c4763416373
 */
object XxteaHelper {

    private const val TAG = "XxteaHelper"
    private val FIXED_KEY_HEX = "67377150343554566b51354736694e6262686155356e586c41656c4763416373"

    /**
     * 解密 oAuth2SdkLoginByPassword 返回的 paras 字段
     * @param hexData paras 的 Hex 编码字符串
     * @return 解密后的 query-string 格式文本 (如 "mobileName=xxx&refreshToken=xxx&userId=xxx&accessToken=xxx")
     */
    fun decryptParas(hexData: String): String {
        val dataBytes = hexToBytes(hexData)
        val keyBytes = hexToBytes(FIXED_KEY_HEX)
        val dataInts = bytesToInts(dataBytes, withLen = false)
        val keyInts = bytesToInts(keyBytes, withLen = false)
        val decryptedInts = xxteaDecrypt(dataInts, keyInts)
        val decryptedBytes = intsToBytes(decryptedInts, withLen = true)
        return utf8ArrayToStr(decryptedBytes)
    }

    // ==================== 底层算法 ====================

    /** a_BZ_I: byte[] → int[] (小端序打包, 可选在末尾存原始长度) */
    private fun bytesToInts(bytes: ByteArray, withLen: Boolean): IntArray {
        val length = if (bytes.size % 4 == 0) bytes.size / 4 else bytes.size / 4 + 1
        val arr = if (withLen) IntArray(length + 1) else IntArray(length)
        if (withLen) {
            arr[length] = bytes.size
        }
        for (i in bytes.indices) {
            val idx = i / 4
            arr[idx] = arr[idx] or ((bytes[i].toInt() and 0xFF) shl ((i % 4) shl 3))
        }
        return arr
    }

    /** a_IZ_B: int[] → byte[] (小端序解包, 可选从末尾读取原始长度) */
    private fun intsToBytes(ints: IntArray, withLen: Boolean): ByteArray {
        val length = ints.size shl 2
        val realLen: Int
        if (withLen) {
            val i = ints[ints.size - 1]
            if (i > length || i <= 0) {
                return ByteArray(0)
            }
            realLen = i
        } else {
            realLen = length
        }
        val bytes = ByteArray(realLen)
        for (i in 0 until realLen) {
            bytes[i] = ((ints[i ushr 2] ushr ((i and 3) shl 3)) and 0xFF).toByte()
        }
        return bytes
    }

    /** b_II_I: XXTEA 解密 */
    private fun xxteaDecrypt(data: IntArray, key: IntArray): IntArray {
        val n = data.size
        if (n < 2) {
            Log.w(TAG, "[xxteaDecrypt] data size=$n < 2, skip decrypt")
            return data
        }

        // key 补齐到 4 个元素
        val k = if (key.size < 4) {
            IntArray(4).also { System.arraycopy(key, 0, it, 0, key.size) }
        } else key

        val delta = -1640531527
        val rounds = 52L / n + 6L
        var sum: Long = rounds * delta.toLong()  // 64-bit 无溢出, 匹配 JS Float64

        // 轮间 carry: 对应 JS 的 let i2 = iArr[0]
        var carryY = data[0]

        while (sum != 0L) {
            val sumInt = toInt32(sum)  // 匹配 JS ToInt32(sum), 不是 sum.toInt()!
            val e = (sumInt ushr 2) and 3
            // 本轮初始 carry: 对应 JS 的 let i4 = i2
            var y = carryY
            var p = n - 1
            while (p > 0) {
                val z = data[p - 1]          // 对应 JS 的 i6 = iArr[i5 - 1]
                val mx = mxCalc(y, z, sumInt, k[(p and 3) xor e])
                y = unsignedSub(data[p], mx)  // y 变为新解密值，carry 到下一次迭代
                data[p] = y
                p--
            }
            // wrap: y 仍是 v[1] 的新解密值
            val z = data[n - 1]              // 对应 JS 的 i7 = iArr[length]
            val mx = mxCalc(y, z, sumInt, k[e])
            carryY = unsignedSub(data[0], mx) // 对应 JS 的 i8
            data[0] = carryY

            // 对应 JS: i -= -1640531527 (64-bit float, 每轮减少 |delta|)
            sum -= delta.toLong()
        }

        return data
    }

    /**
     * 计算单轮 MX 值 (32-bit signed 运算)
     * 标准 XXTEA MX: ((z>>>5 ^ y<<2) + (y>>>3 ^ z<<4)) ^ ((sum ^ y) + (key ^ z))
     */
    private fun mxCalc(y: Int, z: Int, sum: Int, keyVal: Int): Int {
        return (((z ushr 5) xor (y shl 2)) + ((y ushr 3) xor (z shl 4))) xor
                ((sum xor y) + (keyVal xor z))
    }

    /**
     * unsigned arithmetic subtraction: a - b (both treated as u32), result stored as signed Int
     */
    private fun unsignedSub(a: Int, b: Int): Int {
        return (((a.toLong() and 0xFFFFFFFFL) - (b.toLong() and 0xFFFFFFFFL)) and 0xFFFFFFFFL).toInt()
    }

    /** 匹配 JS ToInt32(x): x mod 2^32 后映射到 signed 32-bit */
    private fun toInt32(value: Long): Int {
        val unsigned = Math.floorMod(value, 4294967296L)
        return if (unsigned >= 2147483648L) (unsigned - 4294967296L).toInt() else unsigned.toInt()
    }

    /** 模仿 JS 的 utf8ArrayToStr: 将 UTF-8 字节数组转换为字符串 */
    private fun utf8ArrayToStr(bytes: ByteArray): String {
        // 找到实际结尾 (去除尾部无效字节)
        var end = bytes.size
        while (end > 0 && bytes[end - 1] == 0.toByte()) end--
        return bytes.copyOf(end).toString(Charsets.UTF_8)
    }

    /** Hex 字符串 → ByteArray */
    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) +
                    Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
