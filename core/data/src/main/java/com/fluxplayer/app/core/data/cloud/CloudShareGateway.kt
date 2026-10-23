package com.fluxplayer.app.core.data.cloud

import com.fluxplayer.app.core.data.cloud189.C189ApiClient
import com.fluxplayer.app.core.data.pan123.Pan123ApiClient
import com.fluxplayer.app.core.data.pan123.Pan123ShareFileItem
import org.json.JSONObject

internal interface CloudShareGateway {
    suspend fun open(text: String, password: String): CloudShareSession
    suspend fun list(session: CloudShareSession, parent: String): List<CloudRemoteFile>
    suspend fun save(session: CloudShareSession, files: List<CloudRemoteFile>, target: String)
}

internal class Pan123ShareGateway(private val client: Pan123ApiClient) : CloudShareGateway {
    override suspend fun open(text: String, password: String): CloudShareSession {
        val info = client.parseShareUrl(text) ?: error("请输入有效的 123 分享链接")
        return CloudShareSession(info.first, password.ifBlank { info.second.orEmpty() })
    }

    override suspend fun list(session: CloudShareSession, parent: String): List<CloudRemoteFile> {
        val result = mutableListOf<CloudRemoteFile>()
        val seen = mutableSetOf<String>()
        for (page in 1..200) {
            val listing = client.listShareFiles(session.id, session.password, parent, page).getOrThrow()
            val batch = listing.files.map {
                CloudRemoteFile(
                    it.fileId,
                    it.fileName,
                    it.isDirectory,
                    it.size,
                    jsonObject("etag" to it.etag, "s3" to it.s3keyFlag, "storage" to it.storageNode).toString(),
                    parent,
                )
            }
            val fresh = batch.filter { seen.add(it.id) }
            result.addAll(fresh)
            if (batch.size < 100 || result.size >= listing.total) return result
            check(fresh.isNotEmpty()) { "123 分享分页重复，请刷新重试" }
        }
        error("分享目录过大，请进入子目录")
    }

    override suspend fun save(session: CloudShareSession, files: List<CloudRemoteFile>, target: String) {
        client.copySaveFiles(
            session.id,
            session.password,
            files.map {
                val metadata = JSONObject(it.token)
                Pan123ShareFileItem(it.id, it.name, if (it.directory) 1 else 0, it.size, metadata.optString("etag"), metadata.optString("s3"), metadata.optString("storage"))
            },
            target,
        ).getOrThrow().also { check(it) { "123 转存失败" } }
    }
}

internal class C189ShareGateway(private val client: C189ApiClient) : CloudShareGateway {
    override suspend fun open(text: String, password: String): CloudShareSession {
        val info = client.parseShareUrl(text) ?: error("请输入有效的天翼分享链接")
        val pwd = password.ifBlank { info.sharePwd }
        val data = client.getShareInfoByCode(info.shareKey, pwd)
        return CloudShareSession(data.requiredString("shareId"), pwd, data.toString(), data.requiredString("fileId"))
    }

    override suspend fun list(session: CloudShareSession, parent: String): List<CloudRemoteFile> {
        val info = JSONObject(session.token)
        val result = mutableListOf<CloudRemoteFile>()
        val seen = mutableSetOf<String>()
        for (page in 1..200) {
            val listing = client.listShareDir(session.id, parent, if (parent == session.rootId) info.optBoolean("isFolder") else true, info.optInt("shareMode"), page, 200, session.password)
            val fresh = listing.items.filter { seen.add(it.id) }
            result.addAll(fresh.map { CloudRemoteFile(it.id, it.name, it.isDir, it.size, it.md5, parent) })
            if (listing.items.size < 200 || result.size >= listing.totalCount) return result
            check(fresh.isNotEmpty()) { "天翼分享分页重复，请刷新重试" }
        }
        error("分享目录过大，请进入子目录")
    }

    override suspend fun save(session: CloudShareSession, files: List<CloudRemoteFile>, target: String) {
        files.forEach { file ->
            check(client.shareSave(file.id, file.name, session.id, if (file.directory) 1 else 0, target, session.password)) { "天翼未接受转存请求" }
        }
    }
}
