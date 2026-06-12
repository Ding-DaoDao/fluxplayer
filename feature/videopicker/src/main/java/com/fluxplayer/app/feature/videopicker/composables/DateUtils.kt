package com.fluxplayer.app.feature.videopicker.composables

import java.text.SimpleDateFormat
import java.util.Locale

fun formatRelativeDate(raw: String): String? {
    if (raw.isBlank()) return null
    val millis = parseToMillis(raw) ?: return null
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return sdf.format(millis)
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
