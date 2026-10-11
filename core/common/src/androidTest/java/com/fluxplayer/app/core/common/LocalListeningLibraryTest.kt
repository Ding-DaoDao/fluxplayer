package com.fluxplayer.app.core.common

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 不含文件路径的文档标识，验证扫描不会依赖外部存储路径拼接。 */
class ListeningTestDocuments : DocumentsProvider() {
    override fun onCreate() = true
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        (parentDocumentId == "root-opaque" && documentId in setOf("book-opaque", "audio-ten", "audio-two", "cover-opaque")) ||
            (parentDocumentId == "book-opaque" && documentId in setOf("audio-ten", "audio-two", "cover-opaque"))
    override fun queryRoots(projection: Array<out String>?) = MatrixCursor(projection ?: arrayOf("root_id"))
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = documents(projection, listOf(documentId))
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor = documents(
        projection,
        when (parentDocumentId) {
            "root-opaque" -> listOf("book-opaque")
            "book-opaque" -> listOf("audio-ten", "audio-two", "cover-opaque")
            else -> emptyList()
        },
    )
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor = throw java.io.FileNotFoundException()

    private fun documents(projection: Array<out String>?, ids: List<String>): Cursor {
        val columns = projection ?: arrayOf("document_id", "_display_name", "_size", "last_modified", "mime_type")
        return MatrixCursor(columns).apply {
            ids.forEach { id ->
                val name = when (id) {
                    "book-opaque" -> "测试书籍"
                    "audio-ten" -> "10.mp3"
                    "audio-two" -> "2.mp3"
                    "cover-opaque" -> "COVER.jpg"
                    else -> "书库"
                }
                val type = if (id in setOf("root-opaque", "book-opaque")) DocumentsContract.Document.MIME_TYPE_DIR else if (id == "cover-opaque") "image/jpeg" else "audio/mpeg"
                addRow(
                    columns.map<String, Any?> { column ->
                        when (column) {
                            "document_id" -> id
                            "_display_name" -> name
                            "_size" -> 100L
                            "last_modified" -> 1L
                            "mime_type" -> type
                            else -> null
                        }
                    }.toTypedArray(),
                )
            }
        }
    }
}

class LocalListeningLibraryTest {
    @Test
    fun opaqueDocumentIdsAreReadSortedAndAvailableToBackgroundPlayback() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val root = Uri.parse("content://com.fluxplayer.app.core.common.test.listening/tree/root-opaque")
        val folder = LocalListeningLibrary.children(context, root).single()
        val book = LocalListeningLibrary.scanBook(context, folder)
        assertEquals(listOf("2.mp3", "10.mp3"), book.chapters.map { it.name })
        assertTrue(book.path.startsWith("content://"))
        assertEquals("COVER.jpg", LocalListeningLibrary.children(context, folder.uri).first { it.uri == book.cover }.name)
        assertEquals(book.chapters.map { it.uri }, LocalListeningLibrary.find(context, book.chapters.first().uri.toString())?.chapters?.map { it.uri })
    }

    @Test
    fun externalStorageFileAndDocumentUrisHaveTheSameIdentity() {
        assertEquals("file:///storage/emulated/0/books/02.mp3", LocalListeningLibrary.chapterIdentity("content://com.android.externalstorage.documents/tree/primary%3Abooks/document/primary%3Abooks%2F02.mp3"))
    }
}
