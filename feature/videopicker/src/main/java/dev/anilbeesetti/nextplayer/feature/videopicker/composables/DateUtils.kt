package dev.anilbeesetti.nextplayer.feature.videopicker.composables

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

fun formatRelativeDate(raw: String): String? {
    if (raw.isBlank()) return null
    val millis = parseToMillis(raw) ?: return null

    val now = System.currentTimeMillis()
    val diff = now - millis
    if (diff < 0) return null

    val days = TimeUnit.MILLISECONDS.toDays(diff)
    return when {
        days == 0L -> "今天"
        days == 1L -> "昨天"
        days < 7 -> "${days}天前"
        days < 30 -> "${days / 7}周前"
        days < 365 -> {
            val cal = Calendar.getInstance().apply { timeInMillis = millis }
            "${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日"
        }
        else -> {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            sdf.format(millis)
        }
    }
}

private fun parseToMillis(raw: String): Long? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null

    // Unix 毫秒时间戳字符串 (Quark)
    trimmed.toLongOrNull()?.let { ts ->
        return if (ts > 1_000_000_000_000L) ts else ts * 1000L
    }

    // "yyyy-MM-dd HH:mm:ss" (C189)
    try {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.parse(trimmed)?.time
    } catch (_: Exception) {}

    // ISO 8601 variants
    val normalized = trimmed
        .replace("T", " ")
        .replace(Regex("\\.\\d+"), "") // remove millis
        .replace(Regex("[Zz]"), "")
        .replace(Regex("[+-]\\d{2}:?\\d{2}$"), "")
        .trim()

    // Try "yyyy-MM-dd HH:mm:ss" after normalization
    try {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.parse(normalized)?.time
    } catch (_: Exception) {}

    // Try "yyyy-MM-dd HH:mm" (shortest)
    try {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return sdf.parse(normalized)?.time
    } catch (_: Exception) {}

    // Try date only "yyyy-MM-dd"
    try {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return sdf.parse(normalized)?.time
    } catch (_: Exception) {}

    return null
}
