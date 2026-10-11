package com.fluxplayer.app.feature.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.fluxplayer.app.core.common.LocalListeningLibrary
import java.io.File

/** 使用书库快照恢复章节，旧的文件地址仍支持目录扫描。 */
@androidx.annotation.OptIn(UnstableApi::class)
fun localListeningItems(context: Context, chapter: Uri, cover: Uri? = null): List<MediaItem> {
    val book = LocalListeningLibrary.find(context, chapter.toString())
        ?: LocalListeningLibrary.find(context, chapter.path.orEmpty())
    if (book != null) {
        return book.chapters.map {
            MediaItem.Builder().setUri(it.uri).setMediaId(it.uri.toString())
                .setMediaMetadata(
                    ListeningNotificationArtwork.withFallback(
                        MediaMetadata.Builder().setTitle(it.name.substringBeforeLast('.'))
                            .setAlbumTitle(book.title).setArtist(book.title).setArtworkUri(cover ?: book.cover)
                            .setExtras(android.os.Bundle().apply { putString("local_book_path", book.path) }).build(),
                    ),
                ).build()
        }
    }
    val folder = File(chapter.path.orEmpty()).parentFile
    return scanAudioFiles(folder).map {
        MediaItem.Builder().setUri(Uri.fromFile(it)).setMediaId(it.absolutePath)
            .setMediaMetadata(
                ListeningNotificationArtwork.withFallback(
                    MediaMetadata.Builder().setTitle(it.nameWithoutExtension).setAlbumTitle(folder?.name)
                        .setArtist(folder?.name).setArtworkUri(cover)
                        .setExtras(android.os.Bundle().apply { putString("local_book_path", folder?.absolutePath) }).build(),
                ),
            ).build()
    }
}

fun localListeningBookPath(item: MediaItem?): String? = item?.mediaMetadata?.extras?.getString("local_book_path")
    ?: item?.mediaId?.takeIf { it.startsWith('/') }?.let { File(it).parent }

fun localListeningUri(id: String): Uri = if (id.startsWith('/')) Uri.fromFile(File(id)) else Uri.parse(id)
