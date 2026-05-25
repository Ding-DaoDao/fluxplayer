package dev.anilbeesetti.nextplayer.feature.player.danmaku

import android.content.Context
import android.net.Uri
import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream

private const val TAG = "DanmakuParser"

/**
 * B站弹幕 XML 解析与写入工具。
 *
 * XML 格式兼容 Bilibili 弹幕：
 * ```xml
 * <i>
 *   <d p="time(sec),mode,fontSize,color(dec),timestamp,pool,uid,rowId">text</d>
 *   ...
 * </i>
 * ```
 * 其中 `p` 属性逗号分隔：
 *   0. time    — 出现时间（秒，float）
 *   1. mode    — 1=滚动, 4=底部, 5=顶部, 6=反向
 *   2. fontSize— 字号（25=标准, 18=小, 36=大）
 *   3. color   — 十进制 RGB（如 16777215 = 0xFFFFFF）
 *   4. timestamp — 发送时间戳（unix 秒，可选）
 *   5. pool    — 弹幕池（0=普通, 1=字幕）
 *   6. uid     — 用户 ID
 *   7. rowId   — 弹幕 ID
 */
object DanmakuParser {

    /** 默认弹幕持续时长（毫秒），约 3.8 秒横穿屏幕 */
    const val DEFAULT_DURATION_MS = 3800L

    /**
     * 从 content URI 加载并解析弹幕文件。
     * 自动检测文件格式（XML / JSON），路由到对应解析器。
     * @return 按时间排序的 [Danmaku] 列表，失败返回 null
     */
    suspend fun loadFromUri(context: Context, uri: Uri): List<Danmaku>? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                // 根据文件扩展名选择解析器
                val path = uri.toString().lowercase()
                if (path.endsWith(".json")) {
                    Log.d(TAG, "Detected JSON format: $uri")
                    DanmakuJsonParser.parse(stream)
                } else {
                    Log.d(TAG, "Detected XML format: $uri")
                    parseBilibiliXml(stream)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load danmaku from URI: $uri", e)
            null
        }
    }

    /**
     * 解析 Bilibili XML 弹幕流。
     * 返回按 [Danmaku.timeMs] 升序排列的列表。
     */
    fun parseBilibiliXml(inputStream: InputStream): List<Danmaku> {
        val danmakus = mutableListOf<Danmaku>()

        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(inputStream, "UTF-8")

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "d") {
                    val pAttr = parser.getAttributeValue(null, "p")
                    if (pAttr == null) {
                        eventType = parser.next()
                        continue
                    }
                    val text = parser.nextText().trim()

                    val parts = pAttr.split(",")
                    if (parts.size >= 4) {
                        try {
                            val timeMs = (parts[0].toFloat() * 1000).toLong()
                            val mode = parts[1].toInt()
                            val fontSize = parts[2].toFloat()
                            // B站 color 是十进制 RGB，需转为 ARGB
                            val rgb = parts[3].toInt()
                            val color = rgb or (0xFF shl 24) // 设 alpha=FF

                            danmakus.add(
                                Danmaku(
                                    timeMs = timeMs,
                                    text = text,
                                    mode = when (mode) {
                                        4 -> Danmaku.MODE_BOTTOM
                                        5 -> Danmaku.MODE_TOP
                                        else -> Danmaku.MODE_SCROLL // 1 或 6 都按滚动
                                    },
                                    fontSize = fontSize,
                                    color = color,
                                    index = danmakus.size,
                                ),
                            )

                            if (danmakus.size <= 3 || danmakus.size % 5000 == 0) {
                                Log.d(TAG, "  #${danmakus.size}: t=${timeMs}ms mode=$mode " +
                                        "size=$fontSize text=${text.take(20)}")
                            }
                        } catch (_: NumberFormatException) {
                            // 跳过格式错误的条目
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Bilibili XML", e)
        }

        // 按时间排序
        danmakus.sortBy { it.timeMs }
        Log.d(TAG, "Parsed ${danmakus.size} danmaku items, sorted by time")
        return danmakus
    }

    /**
     * 将一条弹幕追加写入本地 XML 文件。
     * 如果文件不存在或格式不完整，会自动创建 <i>...</i> 根节点。
     */
    suspend fun appendToXml(context: Context, uri: Uri, danmaku: Danmaku) {
        try {
            val line = buildString {
                append("  <d p=\"")
                append(danmaku.timeMs / 1000f)
                append(',')
                append(danmaku.mode)
                append(',')
                append(danmaku.fontSize.toInt())
                append(',')
                append(danmaku.color and 0x00FFFFFF) // 去掉 alpha，存 RGB
                append(",0,0,0,0\">")
                append(escapeXml(danmaku.text))
                append("</d>\n")
            }

            context.contentResolver.openOutputStream(uri, "wa")?.use { out ->
                // "wa" = append mode
                out.write(line.toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to append danmaku to file", e)
        }
    }

    /** 简单 XML 转义 */
    private fun escapeXml(text: String): String = buildString {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }
}
