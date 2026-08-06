package com.fluxplayer.app.feature.player.danmaku

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

private const val TAG = "DanmakuJsonParser"

/**
 * 通用 JSON 弹幕解析器。
 *
 * 支持常见中文视频平台（腾讯/爱奇艺/优酷/B站 JSON 导出）的弹幕格式。
 * 自动嗅探字段名匹配以下常见映射：
 *
 * | 字段   | 可能的名字                                           |
 * |--------|-----------------------------------------------------|
 * | 时间   | time / t / time_offset / offset / showTime          |
 * | 内容   | content / m / text / message / words                |
 * | 模式   | type / mode                                          |
 * | 颜色   | color / c / colour                                   |
 * | 字号   | size / s / fontSize / font_size                      |
 */
object DanmakuJsonParser {

    private const val SECONDS_MS_THRESHOLD = 500.0
    private const val DEFAULT_COLOR = 0xFFFFFF
    private const val DEFAULT_FONT_SIZE = 25f
    private const val FONT_SIZE_MAX = 100f
    private const val ALPHA_MASK = 0xFF shl 24

    private class FieldNames(
        val time: String,
        val text: String,
        val mode: String,
        val color: String,
        val size: String,
    )

    fun parse(inputStream: InputStream): List<Danmaku> {
        val text = inputStream.bufferedReader().use { it.readText() }
        val trimmed = text.trim()

        return when {
            trimmed.startsWith("[") -> parseArray(JSONArray(trimmed))
            trimmed.startsWith("{") -> parseObject(JSONObject(trimmed))
            else -> {
                Log.e(TAG, "Unknown JSON structure: starts with ${trimmed.take(20)}")
                emptyList()
            }
        }
    }

    private fun parseArray(jsonArray: JSONArray): List<Danmaku> {
        val len = jsonArray.length()
        Log.d(TAG, "parseArray: length=$len")
        if (len == 0) return emptyList()

        val first = jsonArray.get(0)
        if (first !is JSONObject) {
            Log.d(TAG, "parseArray: first item is ${first?.javaClass?.simpleName}, not JSONObject")
            return emptyList()
        }
        val fields = detectFields(first) ?: return emptyList()

        val result = mutableListOf<Danmaku>()
        for (i in 0 until len) {
            val obj = jsonArray.optJSONObject(i)
            if (obj == null) {
                if (i < 3) Log.d(TAG, "parseArray: item[$i] is not JSONObject")
                continue
            }
            val item = parseItem(obj, fields)
            if (item != null) {
                result.add(item)
            } else {
                if (i < 3) Log.d(TAG, "parseArray: item[$i] parseItem returned null")
            }
        }

        result.sortBy { it.timeMs }
        Log.d(TAG, "JSON parsed: ${result.size} items (array)")
        return result
    }

    private fun parseObject(jsonObject: JSONObject): List<Danmaku> {
        // 先检查自身是否为一条弹幕
        val selfFields = detectFields(jsonObject)
        if (selfFields != null) {
            val item = parseItem(jsonObject, selfFields)
            if (item != null) {
                Log.d(TAG, "JSON parsed: 1 item (single object)")
                return listOf(item)
            }
        }

        // 递归搜索：遍历所有值，找到 JSONArray 优先解析
        val result = recursiveFindArray(jsonObject)
        if (result.isNotEmpty()) {
            Log.d(TAG, "JSON parsed: ${result.size} items (nested object)")
            return result
        }

        // 特殊的 B站 protobuf 风格：{"items": {"item": [...]}}
        val itemKeys = listOf("danmaku", "danmu", "items", "comments", "list")
        for (key in itemKeys) {
            val nested = jsonObject.optJSONObject(key)
            if (nested != null) {
                val arr = nested.optJSONArray("item") ?: nested.optJSONArray("list") ?: continue
                return parseArray(arr)
            }
        }

        Log.w(TAG, "Could not find any danmaku array in JSON object")
        return emptyList()
    }

    /** 递归遍历 JSONObject 的所有值，找到第一个 JSONArray 并尝试解析 */
    private fun recursiveFindArray(obj: JSONObject): List<Danmaku> {
        val iter = obj.keys()
        while (iter.hasNext()) {
            val key = iter.next()
            try {
                when (val value = obj.get(key)) {
                    is JSONArray -> {
                        if (value.length() > 0 && value.get(0) is JSONObject) {
                            val fields = detectFields(value.getJSONObject(0))
                            if (fields != null) {
                                return parseArray(value)
                            }
                        }
                    }
                    is JSONObject -> {
                        // 递归
                        val sub = recursiveFindArray(value)
                        if (sub.isNotEmpty()) return sub
                    }
                }
            } catch (_: Exception) { }
        }
        return emptyList()
    }

    /** B站 JSON 格式：time/mode/fontSize/color 信息在 "p" 字段的逗号分隔字符串中 */
    private val BILIBILI_JSON_KEYS = setOf("p", "m")

    private fun detectFields(obj: JSONObject): FieldNames? {
        val keysLower = mutableSetOf<String>()
        val iter = obj.keys()
        while (iter.hasNext()) {
            keysLower.add(iter.next().lowercase())
        }
        if (keysLower.isEmpty()) return null

        Log.d(TAG, "detectFields keys: $keysLower")

        // 检测 B站 JSON 格式（"p" + "m"）
        if (BILIBILI_JSON_KEYS.all { it in keysLower }) {
            Log.d(TAG, "detectFields: detected Bilibili JSON format (p+m)")
            return FieldNames("p", "m", "p", "p", "p")
        }

        val timeKey = pickKey(keysLower, listOf("time", "showtime", "prog",
            "timeoffset", "offset", "starttime", "startTime"))
        val textKey = pickKey(keysLower, listOf("content", "m", "text", "message",
            "words", "danmaku", "data", "info"))
        if (timeKey == null || textKey == null) {
            Log.d(TAG, "detectFields: missing required keys (time=$timeKey, text=$textKey)")
            return null
        }

        val modeKey = pickKey(keysLower, listOf("type", "mode"))
        val colorKey = pickKey(keysLower, listOf("color", "c", "colour"))
        val sizeKey = pickKey(keysLower, listOf("size", "s", "fontsize", "font_size"))

        val rawTime = findOriginalKey(obj, timeKey)
        val rawText = findOriginalKey(obj, textKey)
        val rawMode = if (modeKey != null) findOriginalKey(obj, modeKey) else "mode"
        val rawColor = if (colorKey != null) findOriginalKey(obj, colorKey) else "color"
        val rawSize = if (sizeKey != null) findOriginalKey(obj, sizeKey) else "fontSize"

        Log.d(TAG, "detectFields: time='$rawTime' text='$rawText' mode='$rawMode' color='$rawColor' size='$rawSize'")
        return FieldNames(rawTime, rawText, rawMode, rawColor, rawSize)
    }

    private fun parseItem(obj: JSONObject, fields: FieldNames): Danmaku? {
        return try {
            // B站 JSON 格式："p" 字段包含逗号分隔的位置信息
            if (fields.time == "p" && fields.mode == "p") {
                return parseBilibiliJsonItem(obj, fields)
            }

            val rawTime = obj.optDouble(fields.time, -1.0)
            if (rawTime < 0) return null
            // 自动检测单位：> 500 的视为毫秒，否则视为秒
            val timeMs = if (rawTime > SECONDS_MS_THRESHOLD) rawTime.toLong() else (rawTime * 1000).toLong()

            val text = obj.optString(fields.text, "").trim()
            if (text.isEmpty()) return null

            val mode = obj.optInt(fields.mode, 1)
            val colorRgb = obj.optInt(fields.color, DEFAULT_COLOR)
            val fontSize = obj.optDouble(fields.size, DEFAULT_FONT_SIZE.toDouble()).toFloat()

            Danmaku(
                timeMs = timeMs,
                text = text,
                mode = when (mode) {
                    4 -> Danmaku.MODE_BOTTOM
                    5 -> Danmaku.MODE_TOP
                    else -> Danmaku.MODE_SCROLL
                },
                fontSize = fontSize,
                color = colorRgb or ALPHA_MASK,
                index = 0,
            )
        } catch (_: Exception) {
            null
        }
    }

    /** 解析 B站 JSON 格式：`p` 字段 = "time,mode,fontSize,color,..." */
    private fun parseBilibiliJsonItem(obj: JSONObject, fields: FieldNames): Danmaku? {
        val pStr = obj.optString(fields.time, "")
        Log.d(TAG, "parseBilibiliJsonItem: p='${pStr.take(60)}...' text='${obj.optString(fields.text, "").take(20)}'")
        if (pStr.isEmpty()) return null

        val parts = pStr.split(",")
        if (parts.size < 3) {
            Log.d(TAG, "parseBilibiliJsonItem: p only has ${parts.size} parts")
            return null
        }

        val text = obj.optString(fields.text, "").trim()
        if (text.isEmpty()) return null

        return try {
            val timeMs = (parts[0].toFloat() * 1000).toLong()
            val mode = parts[1].toInt()

            // 自适应 p 字段格式：
            //   标准 B站 XML:    time,mode,fontSize,color,...
            //   iQiyi JSON 变体:  time,mode,color,platform,...
            // 判断方法：parts[2] <= 100 视为 fontSize，否则视为 color
            val (fontSize, rgb) = if (parts.size >= 4) {
                val v2 = parts[2].toFloatOrNull()
                val v3 = parts[3].toFloatOrNull()
                if (v2 != null && v2 <= FONT_SIZE_MAX && v3 != null) {
                    // 标准 B站：parts[2]=fontSize, parts[3]=color
                    v2 to v3.toInt()
                } else if (v2 != null && v2 > FONT_SIZE_MAX && v3 == null) {
                    // iQiyi 变体：parts[2]=color，无 fontSize
                    DEFAULT_FONT_SIZE to v2.toInt()
                } else if (v2 != null && v2 > FONT_SIZE_MAX) {
                    // iQiyi 变体：parts[2]=color, parts[3] 非数字或未知
                    DEFAULT_FONT_SIZE to v2.toInt()
                } else {
                    // fallback
                    DEFAULT_FONT_SIZE to DEFAULT_COLOR
                }
            } else {
                // 只有 3 段：time,mode,color
                val v2 = parts[2].toFloatOrNull()
                if (v2 != null && v2 > FONT_SIZE_MAX) {
                    DEFAULT_FONT_SIZE to v2.toInt()
                } else {
                    DEFAULT_FONT_SIZE to DEFAULT_COLOR
                }
            }

            val color = rgb or ALPHA_MASK
            Log.d(TAG, "parseBilibiliJsonItem: OK time=${timeMs}ms mode=$mode fontSize=$fontSize color=#${rgb.toString(16)}")
            Danmaku(
                timeMs = timeMs,
                text = text,
                mode = when (mode) {
                    4 -> Danmaku.MODE_BOTTOM
                    5 -> Danmaku.MODE_TOP
                    else -> Danmaku.MODE_SCROLL
                },
                fontSize = fontSize,
                color = color,
                index = 0,
            )
        } catch (e: Exception) {
            Log.d(TAG, "parseBilibiliJsonItem: parse failed: ${e.message}")
            null
        }
    }

    /** 从候选列表中找出第一个存在的 key（不区分大小写），返回原始大小写 */
    private fun pickKey(keysLower: Set<String>, candidates: List<String>): String? {
        for (c in candidates) {
            if (c.lowercase() in keysLower) return c
        }
        return null
    }

    /** 从原 JSONObject 中找某 key 的原始大小写 */
    private fun findOriginalKey(obj: JSONObject, lowercaseKey: String): String {
        val iter = obj.keys()
        while (iter.hasNext()) {
            val key = iter.next()
            if (key.equals(lowercaseKey, ignoreCase = true)) return key
        }
        return lowercaseKey
    }
}
