package dev.anilbeesetti.nextplayer.feature.player.danmaku

import android.content.Context
import android.net.Uri
import android.util.Log
import master.flame.danmaku.danmaku.parser.BaseDanmakuParser
import master.flame.danmaku.danmaku.model.IDanmakus
import master.flame.danmaku.danmaku.model.BaseDanmaku
import master.flame.danmaku.danmaku.model.Duration
import master.flame.danmaku.danmaku.model.R2LDanmaku
import master.flame.danmaku.danmaku.model.FTDanmaku
import master.flame.danmaku.danmaku.model.FBDanmaku
import master.flame.danmaku.danmaku.model.L2RDanmaku
import master.flame.danmaku.danmaku.model.android.Danmakus
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream

/**
 * Utility object for loading and managing danmaku data.
 */
object DanmakuManager {

    private const val TAG = "DanmakuManager"

    /** Default danmaku duration in ms (B站 standard) */
    private const val DEFAULT_DURATION = 3800L

    /**
     * Load a Bilibili XML danmaku file from a content URI.
     * Returns null if loading fails.
     */
    suspend fun loadFromUri(context: Context, uri: Uri): BaseDanmakuParser? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                loadFromStream(stream)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load danmaku from URI", e)
            null
        }
    }

    /**
     * Parse a Bilibili XML stream into a [BaseDanmakuParser].
     */
    fun loadFromStream(inputStream: InputStream): BaseDanmakuParser {
        val danmakus = parseBilibiliXml(inputStream)
        val collection = Danmakus()
        danmakus.forEach { collection.addItem(it) }

        Log.d(TAG, "Parsed ${danmakus.size} danmaku items from XML")
        return object : BaseDanmakuParser() {
            override fun parse(): IDanmakus {
                Log.d(TAG, "Parser.parse() called on thread ${Thread.currentThread().name}, " +
                        "returning ${collection.size()} items, " +
                        "mContext= ${mContext}, mDispDensity=${mDispDensity}")
                // DFM requires mGlobalFlagValues on every BaseDanmaku for
                // its cache-building thread (BaseDanmaku.hasPassedFilter()).
                // The context is injected by DrawHandler before calling parse().
                mContext?.mGlobalFlagValues?.let { gfv ->
                    danmakus.forEach {
                        it.flags = gfv
                        // Scale text size: B站 formula = fontSize * (density - 0.5)
                        if (it.textSize > 0f) {
                            it.textSize *= (mDispDensity - 0.5f)
                        }
                    }
                }
                return collection
            }
        }
    }

    /**
     * Parse Bilibili XML format and return a list of [BaseDanmaku] items.
     *
     * Format: `<d p="timestamp,type,fontSize,color,timestamp2,pool,userID,rowID">text</d>`
     *   - timestamp: seconds as float
     *   - type: 1=scroll, 4=bottom, 5=top, 6=reverse
     *   - color: hex int (0xRRGGBB)
     */
    private fun parseBilibiliXml(inputStream: InputStream): List<BaseDanmaku> {
        val danmakus = mutableListOf<BaseDanmaku>()

        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(inputStream, "UTF-8")

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "d") {
                    val pAttr = parser.getAttributeValue(null, "p") ?: run {
                        eventType = parser.next()
                        continue
                    }
                    val text = parser.nextText()

                    val parts = pAttr.split(",")
                    if (parts.size >= 4) {
                        try {
                            val timeMs = (parts[0].toFloat() * 1000).toLong()
                            val type = parts[1].toInt()
                            val fontSize = parts[2].toFloat()  // B站 small=25
                            val color = parts[3].toInt()

                            val danmaku = createBaseDanmaku(type)
                            danmaku.text = text
                            danmaku.time = timeMs
                            danmaku.textSize = fontSize  // store raw, scaled in parse()
                            // B站 stores color as 0xRRGGBB (decimal string).
                            // DFM's textColor interprets as 0xAARRGGBB, so set alpha=FF.
                            danmaku.textColor = color or (0xFF shl 24)
                            danmaku.index = danmakus.size
                            danmakus.add(danmaku)

                            // Log first few + periodic samples for debugging timestamp distribution
                            if (danmakus.size <= 3 || danmakus.size % 5000 == 0) {
                                Log.d(TAG, "  danmaku #${danmakus.size}: time=${timeMs}ms " +
                                        "type=${type} fontSize=${fontSize} " +
                                        "color=#${String.format("%08X", danmaku.textColor)} text=${text.take(20)}")
                            }
                        } catch (e: NumberFormatException) {
                            // skip malformed entry
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Bilibili XML", e)
        }

        return danmakus
    }

    /**
     * Create a [BaseDanmaku] of the given Bilibili type.
     *
     * Bilibili type mapping:
     *   1 → scroll right-to-left (R2LDanmaku)
     *   4 → fixed bottom (FBDanmaku)
     *   5 → fixed top (FTDanmaku)
     *   6 → scroll left-to-right (L2RDanmaku)
     */
    private fun createBaseDanmaku(biliType: Int): BaseDanmaku {
        val duration = Duration(DEFAULT_DURATION)
        return when (biliType) {
            4 -> FBDanmaku(duration)
            5 -> FTDanmaku(duration)
            6 -> L2RDanmaku(duration)
            else -> R2LDanmaku(duration) // 1 or default
        }
    }

    /**
     * Convert [DanmakuItem] list to a [BaseDanmakuParser] for custom formats (JSON, etc.).
     */
    fun createParserFromItems(items: List<DanmakuItem>): BaseDanmakuParser {
        val danmakus = Danmakus()
        items.forEach { item ->
            val duration = Duration(DEFAULT_DURATION)
            val danmaku: BaseDanmaku = when (item.type) {
                DanmakuType.TOP -> FTDanmaku(duration)
                DanmakuType.BOTTOM -> FBDanmaku(duration)
                else -> R2LDanmaku(duration) // SCROLL
            }
            danmaku.text = item.text
            danmaku.time = item.timeMs
            danmaku.textColor = item.color or (0xFF shl 24)
            danmaku.index = danmakus.size()
            danmakus.addItem(danmaku)
        }
        return object : BaseDanmakuParser() {
            override fun parse(): IDanmakus {
                // Set mGlobalFlagValues from the parser's context (injected by DrawHandler)
                mContext?.mGlobalFlagValues?.let { gfv ->
                    danmakus.items.forEach {
                        it.flags = gfv
                        // Default font size for programmatic items if not set
                        if (it.textSize <= 0f) {
                            it.textSize = 25f * (mDispDensity - 0.5f)
                        }
                    }
                }
                return danmakus
            }
        }
    }
}
