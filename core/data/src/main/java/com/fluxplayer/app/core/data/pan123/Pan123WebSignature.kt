package com.fluxplayer.app.core.data.pan123

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.CRC32

internal object Pan123WebSignature {
    fun sign(path: String, epochSeconds: Long, nonce: Int): Pair<String, String> {
        val minute = SimpleDateFormat("yyyyMMddHHmm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date((epochSeconds + 57600) * 1000))
        val table = "adefghlmyijnopkqrstubcvwsz"
        val key = crc32(minute.map { table[it.digitToInt()] }.joinToString(""))
        return key to "$epochSeconds-$nonce-${crc32("$epochSeconds|$nonce|$path|web|3|$key")}"
    }

    fun crc32(value: String): String = CRC32().apply { update(value.toByteArray(Charsets.UTF_8)) }.value.toString(16)
}
