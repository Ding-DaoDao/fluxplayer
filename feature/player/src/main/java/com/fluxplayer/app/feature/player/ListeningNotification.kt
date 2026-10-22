package com.fluxplayer.app.feature.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.io.ByteArrayOutputStream

/** 在线听书仅提供一组章节切换按钮，播放暂停由系统生成。 */
@androidx.annotation.OptIn(UnstableApi::class)
fun listeningNotificationButtons(previous: SessionCommand? = null, next: SessionCommand? = null): List<CommandButton> = listOf(
    CommandButton.Builder(CommandButton.ICON_PREVIOUS).setDisplayName("上一章").apply {
        if (previous != null) setSessionCommand(previous) else setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
    }.setSlots(CommandButton.SLOT_BACK).build(),
    CommandButton.Builder(CommandButton.ICON_NEXT).setDisplayName("下一章").apply {
        if (next != null) setSessionCommand(next) else setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
    }.setSlots(CommandButton.SLOT_FORWARD).build(),
)

/** 无封面时提供可解码的统一书籍图片，供系统媒体卡片和通知共同使用。 */
object ListeningNotificationArtwork {
    private const val SIZE = 512

    /** 与列表默认封面同一套书脊配色，保证同书在不同位置颜色一致。 */
    private val palette = intArrayOf(
        Color.rgb(62, 84, 120),
        Color.rgb(60, 98, 76),
        Color.rgb(107, 83, 47),
        Color.rgb(84, 70, 104),
        Color.rgb(51, 90, 99),
        Color.rgb(112, 64, 75),
    )

    private val titleCache = object : LinkedHashMap<String, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean = size > 8
    }

    val bitmap: Bitmap by lazy {
        Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(40, 52, 69))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(220, 230, 241) }
            canvas.drawRoundRect(140f, 102f, 372f, 410f, 22f, 22f, paint)
            paint.color = Color.rgb(84, 113, 150)
            canvas.drawRect(161f, 102f, 178f, 410f, paint)
            paint.strokeWidth = 12f
            canvas.drawLine(213f, 179f, 331f, 179f, paint)
            canvas.drawLine(213f, 221f, 307f, 221f, paint)
        }
    }
    val bytes: ByteArray by lazy { encode(bitmap) }

    /** 按书名画一张默认封面：书脊、内封描边、书名签和底部微章，版式与界面内一致。 */
    fun bytesFor(title: String): ByteArray {
        val key = title.trim().ifEmpty { return bytes }
        synchronized(titleCache) { titleCache[key]?.let { return it } }
        val data = encode(drawCover(key), recycle = true)
        synchronized(titleCache) { titleCache[key] = data }
        return data
    }

    private fun encode(source: Bitmap, recycle: Boolean = false): ByteArray = ByteArrayOutputStream().use { output ->
        source.compress(Bitmap.CompressFormat.PNG, 100, output)
        if (recycle) source.recycle()
        output.toByteArray()
    }

    private fun drawCover(title: String): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val base = palette[Math.floorMod(title.hashCode(), palette.size)]
        val side = SIZE.toFloat()
        val spineWidth = 26f

        canvas.drawPaint(Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, side,
                intArrayOf(lighten(base, 0.10f), base, darken(base, 0.10f)),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        })
        canvas.drawRect(
            0f, 0f, spineWidth, side,
            Paint().apply {
                shader = LinearGradient(
                    0f, 0f, spineWidth, 0f,
                    intArrayOf(Color.argb(72, 0, 0, 0), Color.argb(16, 0, 0, 0), Color.TRANSPARENT),
                    null,
                    Shader.TileMode.CLAMP,
                )
            },
        )
        canvas.drawRect(
            spineWidth, 0f, spineWidth + 1f, side,
            Paint().apply { color = Color.argb(28, 255, 255, 255) },
        )
        canvas.drawRoundRect(
            RectF(spineWidth + 22f, 26f, side - 26f, side - 26f),
            10f,
            10f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = Color.argb(56, 255, 255, 255)
            },
        )

        val contentWidth = (side - spineWidth - 40f).toInt()
        var fontSize = 62f
        var layout = buildTitleLayout(title, fontSize, contentWidth)
        while (layout.height > 300 && fontSize > 30f) {
            fontSize -= 4f
            layout = buildTitleLayout(title, fontSize, contentWidth)
        }
        val left = ((spineWidth + 20f + (contentWidth - layout.width) / 2f)).toInt()
        canvas.save()
        canvas.translate(
            left.toFloat(),
            (side * 0.34f - layout.height / 2f).coerceAtLeast(120f),
        )
        layout.draw(canvas)
        canvas.restore()

        val stampPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.6f
            color = Color.argb(96, 255, 255, 255)
        }
        canvas.drawRoundRect(RectF(side / 2f - 40f, side - 74f, side / 2f + 40f, side - 40f), 6f, 6f, stampPaint)
        val stampText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 255, 255, 255)
            textSize = 26f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("有声书", side / 2f, side - 51f, stampText)
        return bitmap
    }

    private fun buildTitleLayout(title: String, fontSize: Float, width: Int): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(240, 255, 255, 255)
            textSize = fontSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.04f
        }
        return StaticLayout.Builder.obtain(title, 0, title.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.25f)
            .setMaxLines(3)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
    }

    private fun lighten(color: Int, amount: Float): Int = Color.rgb(
        (Color.red(color) + (255 - Color.red(color)) * amount).toInt(),
        (Color.green(color) + (255 - Color.green(color)) * amount).toInt(),
        (Color.blue(color) + (255 - Color.blue(color)) * amount).toInt(),
    )

    private fun darken(color: Int, amount: Float): Int = Color.rgb(
        (Color.red(color) * (1f - amount)).toInt(),
        (Color.green(color) * (1f - amount)).toInt(),
        (Color.blue(color) * (1f - amount)).toInt(),
    )

    /** 缩小通知封面，避免把原图经由媒体会话传输时超过系统消息大小限制。 */
    fun thumbnail(data: ByteArray): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "封面图片无效" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
        val decoded = checkNotNull(BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample }))
        val factor = minOf(1f, 512f / maxOf(decoded.width, decoded.height))
        val scaled = Bitmap.createScaledBitmap(decoded, (decoded.width * factor).toInt().coerceAtLeast(1), (decoded.height * factor).toInt().coerceAtLeast(1), true)
        return try {
            ByteArrayOutputStream().use { output ->
                check(scaled.compress(Bitmap.CompressFormat.JPEG, 85, output)) { "通知封面转换失败" }
                output.toByteArray()
            }
        } finally {
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }

    fun withFallback(metadata: MediaMetadata): MediaMetadata =
        if (metadata.artworkUri != null || metadata.artworkData != null) {
            metadata
        } else {
            val album = metadata.albumTitle?.toString().orEmpty()
            metadata.buildUpon()
                .setArtworkData(bytesFor(album), MediaMetadata.PICTURE_TYPE_FRONT_COVER).build()
        }
}

/** 使用较清晰的封面数据，避免向系统媒体卡片只提供小缩略图。 */
@androidx.annotation.OptIn(UnstableApi::class)
fun listeningBitmapLoader(context: Context): BitmapLoader {
    val source = DataSourceBitmapLoader.Builder(context).setMaximumOutputDimension(1024).build()
    return CacheBitmapLoader(object : BitmapLoader {
        override fun supportsMimeType(mimeType: String): Boolean = source.supportsMimeType(mimeType)
        override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = safely { source.decodeBitmap(data) }
        override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = safely { source.loadBitmap(uri) }
        private fun safely(load: () -> ListenableFuture<Bitmap>): ListenableFuture<Bitmap> = try {
            fallback(load())
        } catch (_: Exception) {
            Futures.immediateFuture(ListeningNotificationArtwork.bitmap)
        }
        private fun fallback(future: ListenableFuture<Bitmap>): ListenableFuture<Bitmap> =
            Futures.catching(future, Exception::class.java, { ListeningNotificationArtwork.bitmap }, MoreExecutors.directExecutor())
    })
}
