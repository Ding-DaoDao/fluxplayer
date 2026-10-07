package com.fluxplayer.app.core.media.sync

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.provider.MediaStore
import android.util.Log
import coil3.ImageLoader
import com.fluxplayer.app.core.common.Dispatcher
import com.fluxplayer.app.core.common.LOCAL_VIDEO_THUMBNAIL_PREFIX
import com.fluxplayer.app.core.common.NextDispatchers
import com.fluxplayer.app.core.common.di.ApplicationScope
import com.fluxplayer.app.core.common.extensions.VIDEO_COLLECTION_URI
import com.fluxplayer.app.core.common.extensions.getStorageVolumes
import com.fluxplayer.app.core.common.extensions.prettyName
import com.fluxplayer.app.core.common.extensions.scanPaths
import com.fluxplayer.app.core.common.extensions.scanStorage
import com.fluxplayer.app.core.database.entities.DirectoryEntity
import com.fluxplayer.app.core.database.entities.MediumEntity
import com.fluxplayer.app.core.media.model.MediaVideo
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LocalMediaSynchronizer @Inject constructor(
    private val snapshotWriter: MediaSnapshotWriter,
    private val imageLoader: ImageLoader,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @ApplicationContext private val context: Context,
    @Dispatcher(NextDispatchers.IO) private val dispatcher: CoroutineDispatcher,
) : MediaSynchronizer {

    private var mediaSyncingJob: Job? = null
    private val syncMutex = Mutex()

    override suspend fun refresh(path: String?): Boolean {
        return path?.let { context.scanPaths(listOf(path)) }
            ?: context.getStorageVolumes().all { context.scanStorage(it.path) }
    }

    @Synchronized
    override fun startSync() {
        if (mediaSyncingJob?.isActive == true) return
        mediaSyncingJob = mediaChanges().onEach {
            syncMutex.withLock {
                try {
                    withContext(dispatcher) {
                        // 查询失败时不提交空快照；成功的空列表则清理最后一条媒体。
                        val media = getMediaVideo(null, null, "${MediaStore.Video.Media.DISPLAY_NAME} ASC")
                        val directories = buildDirectories(media)
                        val entities = media.map { video ->
                            val file = File(video.data)
                            MediumEntity(
                                uriString = video.uri.toString(),
                                path = video.data,
                                name = file.name,
                                parentPath = file.parent ?: "/",
                                modified = video.dateModified,
                                size = video.size,
                                width = video.width,
                                height = video.height,
                                duration = video.duration,
                                mediaStoreId = video.id,
                            )
                        }
                        val cleanup = snapshotWriter.write(entities, directories)
                        cleanup.mediaUris.forEach { uri ->
                            try {
                                imageLoader.diskCache?.remove(LOCAL_VIDEO_THUMBNAIL_PREFIX + uri)
                                imageLoader.diskCache?.remove(uri)
                            } catch (error: Exception) {
                                Log.w("MediaSynchronizer", "清理缩略图失败", error)
                            }
                        }
                        cleanup.subtitleUris.forEach { uri ->
                            try {
                                context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            } catch (error: Exception) {
                                Log.w("MediaSynchronizer", "释放字幕权限失败", error)
                            }
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.e("MediaSynchronizer", "媒体同步失败，保留原数据并等待下次变更", error)
                }
            }
        }.launchIn(applicationScope)
    }

    @Synchronized
    override fun stopSync() {
        mediaSyncingJob?.cancel()
        mediaSyncingJob = null
    }

    private fun buildDirectories(media: List<MediaVideo>): List<DirectoryEntity> {
        val roots = context.getStorageVolumes().map { it.path }.toSet()
        val directories = LinkedHashMap<String, DirectoryEntity>()
        // 从媒体路径直接收集祖先目录，避免递归扫描无关文件夹和反复遍历媒体列表。
        media.forEach { video ->
            var folder = File(video.data).parentFile
            val root = roots.filter { video.data.startsWith("$it/") }.maxByOrNull { it.length }
            if (root != null) {
                while (folder != null && folder.path !in directories) {
                    directories[folder.path] = DirectoryEntity(
                        path = folder.path,
                        name = folder.prettyName,
                        modified = folder.lastModified(),
                        parentPath = if (folder.path == root) "/" else folder.parent ?: "/",
                    )
                    if (folder.path == root) break
                    folder = folder.parentFile
                }
            }
        }
        return directories.values.toList()
    }

    @OptIn(FlowPreview::class)
    private fun mediaChanges(): Flow<Boolean> = callbackFlow {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                // 回调只发送信号，实际查询与写库在同一个串行收集任务中执行。
                trySend(false)
            }
        }
        context.contentResolver.registerContentObserver(VIDEO_COLLECTION_URI, true, observer)
        trySend(true)
        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }.buffer(Channel.CONFLATED).debounce { initial -> if (initial) 0L else 300L }.flowOn(dispatcher)

    private fun getMediaVideo(
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): List<MediaVideo> {
        val mediaVideos = mutableListOf<MediaVideo>()
        val cursor = context.contentResolver.query(
            VIDEO_COLLECTION_URI,
            VIDEO_PROJECTION,
            selection,
            selectionArgs,
            sortOrder,
        ) ?: error("媒体查询未返回有效游标")
        cursor.use {
            val idColumn = cursor.getColumnIndex(MediaStore.Video.Media._ID)
            val dataColumn = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
            val durationColumn = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
            val widthColumn = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH)
            val heightColumn = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT)
            val sizeColumn = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
            val dateModifiedColumn = cursor.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                mediaVideos.add(
                    MediaVideo(
                        id = id,
                        data = cursor.getString(dataColumn),
                        duration = cursor.getLong(durationColumn),
                        uri = ContentUris.withAppendedId(VIDEO_COLLECTION_URI, id),
                        width = cursor.getInt(widthColumn),
                        height = cursor.getInt(heightColumn),
                        size = cursor.getLong(sizeColumn),
                        dateModified = cursor.getLong(dateModifiedColumn),
                    ),
                )
            }
        }
        return mediaVideos.filter { File(it.data).exists() }
    }

    companion object {
        val VIDEO_PROJECTION = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED,
        )
    }
}
