package com.fluxplayer.app.core.common

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 云盘文件下载助手 — 使用系统 DownloadManager 下载文件
 */
@Singleton
class CloudDownloadHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun downloadFile(
        url: String,
        fileName: String,
        mimeType: String = "application/octet-stream",
        headers: Map<String, String> = emptyMap()
    ): Long {
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(fileName)
            setDescription("正在下载 $fileName")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            setMimeType(mimeType)
            headers.forEach { (key, value) ->
                addRequestHeader(key, value)
            }
        }
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return dm.enqueue(request)
    }

    fun downloadFileWithAuth(
        url: String,
        fileName: String,
        authHeader: String,
        mimeType: String = "application/octet-stream"
    ): Long {
        return downloadFile(
            url = url,
            fileName = fileName,
            mimeType = mimeType,
            headers = mapOf("Authorization" to authHeader)
        )
    }
}
