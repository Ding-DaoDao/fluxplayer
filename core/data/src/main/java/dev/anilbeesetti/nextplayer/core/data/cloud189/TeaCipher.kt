package dev.anilbeesetti.nextplayer.core.data.cloud189

import android.util.Log

object TeaCipher {
    private const val TAG = "TeaCipher"

    private fun bytesToInts(b: ByteArray): IntArray {
        val n = if (b.size % 4 == 0) b.size / 4 else (b.size / 4) + 1
        val a = IntArray(n)
        for (i in b.indices) {
            a[i / 4] = a[i / 4] or ((b[i].toInt() and 0xFF) shl ((i % 4) * 8))
        }
        return a
    }

    private fun intsToBytes(a: IntArray, len: Int): ByteArray {
        val b = ByteArray(len)
        for (i in 0 until len) {
            b[i] = ((a[i / 4] ushr ((i % 4) * 8)) and 0xFF).toByte()
        }
        return b
    }

    fun decrypt(ciphertext: ByteArray, key: ByteArray): ByteArray {
        val v = bytesToInts(ciphertext)
        val k = bytesToInts(key)
        val n = v.size
        if (n < 2) return ciphertext

        val rounds = (52 / n) + 6
        var y = v[0]
        var sum = rounds * (-1640531527)
        while (sum != 0) {
            val e = (sum ushr 2) and 3
            var p = n - 1
            while (p > 0) {
                val z = v[p - 1]
                v[p] = (v[p] - (((y xor sum) + (z xor k[(p and 3) xor e])) xor
                        (((z ushr 5) xor (y shl 2)) + ((y ushr 3) xor (z shl 4))))) ushr 0
                y = v[p]
                p--
            }
            val z = v[n - 1]
            v[0] = (v[0] - (((k[(p and 3) xor e] xor z) + (y xor sum)) xor
                    (((z ushr 5) xor (y shl 2)) + ((y ushr 3) xor (z shl 4))))) ushr 0
            y = v[0]
            sum += 1640531527
        }

        val originalLen = v.last()
        return intsToBytes(v, originalLen)
    }

    fun hexToBytes(hex: String): ByteArray {
        val result = ByteArray(hex.length / 2)
        for (i in result.indices) {
            result[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return result
    }

    fun bytesToUtf8(b: ByteArray): String = String(b, Charsets.UTF_8)
}
