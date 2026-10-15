package com.fluxplayer.app.feature.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
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
    val bitmap: Bitmap by lazy {
        Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).also { bitmap ->
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
    val bytes: ByteArray by lazy {
        ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
    }

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
            metadata.buildUpon()
                .setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER).build()
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
