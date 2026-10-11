package com.fluxplayer.app.core.common

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

data class ListeningFile(val uri: Uri, val name: String, val size: Long, val modified: Long, val directory: Boolean)
data class LocalListeningBook(val path: String, val title: String, val chapters: List<ListeningFile>, val cover: Uri?)

/** 目录授权直接用于读取文档，章节快照供前台与后台共用，避免传递超大的播放列表。 */
object LocalListeningLibrary {
    private val audio = setOf("mp3", "m4a", "aac", "ogg", "wav", "flac", "wma", "opus")
    private val images = setOf("jpg", "jpeg", "png", "webp", "bmp")

    fun chapterIdentity(uri: String): String {
        val parsed = Uri.parse(uri)
        if (parsed.scheme == "content" && parsed.authority == "com.android.externalstorage.documents") {
            val id = DocumentsContract.getDocumentId(parsed)
            val volume = id.substringBefore(':')
            return Uri.fromFile(File("${if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"}/${id.substringAfter(':')}")).toString()
        }
        return if (uri.startsWith('/')) Uri.fromFile(File(uri)).toString() else uri
    }

    fun children(context: Context, directory: Uri): List<ListeningFile> {
        if (directory.scheme != "content") {
            val folder = File(directory.path ?: directory.toString())
            require(folder.isDirectory && folder.canRead()) { "书库目录不可访问，请检查存储设备或重新选择目录" }
            return requireNotNull(folder.listFiles()) { "无法读取书库目录" }.map {
                ListeningFile(Uri.fromFile(it), it.name, it.length(), it.lastModified(), it.isDirectory)
            }
        }
        val id = if (DocumentsContract.isDocumentUri(context, directory)) DocumentsContract.getDocumentId(directory) else DocumentsContract.getTreeDocumentId(directory)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(directory, id)
        val columns = arrayOf("document_id", "_display_name", "_size", "last_modified", "mime_type")
        return requireNotNull(context.contentResolver.query(childrenUri, columns, null, null, null)) { "无法读取书库目录，请重新授权" }.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(ListeningFile(DocumentsContract.buildDocumentUriUsingTree(directory, cursor.getString(0)), cursor.getString(1).orEmpty(), cursor.getLong(2), cursor.getLong(3), cursor.getString(4) == DocumentsContract.Document.MIME_TYPE_DIR))
                }
            }
        }
    }

    fun scanBook(context: Context, folder: ListeningFile): LocalListeningBook {
        val files = children(context, folder.uri).filter { !it.directory && !it.name.startsWith('.') }
        val chapters = files.filter { it.name.substringAfterLast('.').lowercase() in audio }
            .sortedWith(compareBy(NaturalOrder.fileNameComparator) { it.name })
        val covers = files.filter { it.name.substringAfterLast('.').lowercase() in images }.sortedBy { it.name.lowercase() }
        val cover = covers.firstOrNull { it.name.substringBeforeLast('.').equals("cover", true) } ?: covers.firstOrNull()
        // 外部存储保留旧版书籍路径，以便迁移原有进度；实际读取仍使用授权 URI。
        val path = if (folder.uri.scheme == "file") {
            folder.uri.path!!
        } else if (folder.uri.authority == "com.android.externalstorage.documents") {
            val id = DocumentsContract.getDocumentId(folder.uri)
            val volume = id.substringBefore(':')
            "${if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"}/${id.substringAfter(':')}"
        } else {
            folder.uri.toString()
        }
        return LocalListeningBook(path, folder.name, chapters, cover?.uri).also { remember(context, it) }
    }

    @Synchronized
    fun remember(context: Context, book: LocalListeningBook) {
        val id = digest(book.path)
        val directory = File(context.filesDir, "local_listening").apply { mkdirs() }
        val data = JSONObject().put("path", book.path).put("title", book.title).put("cover", book.cover?.toString())
            .put("chapters", JSONArray(book.chapters.map { JSONObject().put("uri", it.uri.toString()).put("name", it.name).put("size", it.size) }))
        val temp = File(directory, "$id.tmp")
        temp.writeText(data.toString())
        check(temp.renameTo(File(directory, "$id.json"))) { "保存书库索引失败" }
        val editor = context.getSharedPreferences("local_listening_index", Context.MODE_PRIVATE).edit()
        book.chapters.forEach { editor.putString(it.uri.toString(), id) }
        editor.putString(book.path, id).apply()
    }

    fun find(context: Context, chapterOrBook: String): LocalListeningBook? {
        val id = context.getSharedPreferences("local_listening_index", Context.MODE_PRIVATE).getString(chapterOrBook, null) ?: return null
        return runCatching {
            val data = JSONObject(File(context.filesDir, "local_listening/$id.json").readText())
            val chapters = data.getJSONArray("chapters")
            LocalListeningBook(
                data.getString("path"),
                data.getString("title"),
                List(chapters.length()) { index ->
                    val chapter = chapters.getJSONObject(index)
                    ListeningFile(Uri.parse(chapter.getString("uri")), chapter.getString("name"), chapter.optLong("size"), 0, false)
                },
                data.optString("cover").takeIf { it.isNotBlank() }?.let(Uri::parse),
            )
        }.getOrNull()
    }

    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
