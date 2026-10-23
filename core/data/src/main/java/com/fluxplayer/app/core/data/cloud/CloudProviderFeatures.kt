package com.fluxplayer.app.core.data.cloud

import com.fluxplayer.app.core.data.CloudHttpClient
import com.fluxplayer.app.core.data.aliyun.AliyunApiClient
import com.fluxplayer.app.core.data.cloud189.C189ApiClient
import com.fluxplayer.app.core.data.pan123.Pan123ApiClient
import com.fluxplayer.app.core.data.quark.QuarkApiClient
import com.fluxplayer.app.core.data.yun139.Yun139ApiClient
import com.fluxplayer.app.core.data.yun139.Yun139AuthProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/** 把列表项的元数据（etag/s3keyFlag）还原成 123 下载接口需要的条目。 */
private fun pan123Item(file: CloudRemoteFile): com.fluxplayer.app.core.data.pan123.Pan123FileItem {
    val metadata = runCatching { JSONObject(file.token) }.getOrElse { JSONObject() }
    return com.fluxplayer.app.core.data.pan123.Pan123FileItem(
        fileId = file.id,
        fileName = file.name,
        type = if (file.directory) 1 else 0,
        size = file.size,
        etag = metadata.optString("etag"),
        s3keyFlag = metadata.optString("s3"),
        downloadUrl = "",
        createAt = "",
        trashedAt = "",
        starredStatus = 0,
    )
}

/** 扩展功能复用当前浏览器的客户端和登录态，避免维护第二份账号凭据。 */
class CloudProviderFeatures private constructor(
    val provider: String,
    internal val call: suspend (String, JSONObject?, Map<String, String>, Map<String, String>) -> JSONObject,
    internal val driveId: () -> String = { "" },
    private val folderLoader: suspend (String, Int) -> Pair<List<CloudRemoteFile>, Boolean>,
    private val legacyShare: CloudShareGateway? = null,
    private val resolvePersonal: suspend (CloudRemoteFile) -> String,
    internal val httpClient: okhttp3.OkHttpClient = CloudHttpClient.DEFAULT,
    internal val mediaHeaders: () -> Map<String, String> = { emptyMap() },
    private val resolveDownload: suspend (CloudRemoteFile) -> String = resolvePersonal,
) {
    companion object {
        fun from(client: AliyunApiClient) = CloudProviderFeatures("alipan", { path, body, _, headers ->
            client.featureRequest(path, body ?: JSONObject(), headers)
        }, { client.driveId }, { parent, page ->
            // 阿里使用游标分页，目标目录直接走独立的完整读取流程。
            val result = mutableListOf<CloudRemoteFile>()
            var marker: String? = null
            val markers = mutableSetOf<String>()
            do {
                val listing = client.listFiles(parent, nextMarker = marker).getOrThrow()
                result.addAll(listing.items.map { CloudRemoteFile(it.fileId, it.fileName, it.type == "folder", it.size, parentId = parent) })
                marker = listing.nextMarker.takeIf { it.isNotBlank() }
                check(marker == null || markers.add(marker)) { "目录分页重复，请重试" }
                check(markers.size < 200) { "目录过大，请缩小范围" }
            } while (marker != null)
            result to false
        }, resolvePersonal = { file -> client.getDownloadUrl(file.id).getOrThrow() }, httpClient = client.featureHttpClient)

        /** [type] 必须与 client 的来源一致；默认直接取客户端来源，避免两处传参不一致。 */
        fun from(client: QuarkApiClient, type: String = client.provider) = CloudProviderFeatures(type, { path, body, query, headers ->
            client.featureRequest(path, body, query, headers)
        }, folderLoader = { parent, page ->
            val items = client.listFiles(parent, page).getOrThrow()
            items.map { CloudRemoteFile(it.fid, it.fileName, it.dir, it.size, parentId = parent) } to (items.size >= client.pageSize)
        }, resolvePersonal = { file -> client.resolvePlaybackUrl(file.id, file.name) }, httpClient = client.featureHttpClient, mediaHeaders = { client.featureHeaders() }, resolveDownload = { client.getDownloadUrl(it.id).getOrThrow() })

        fun from(client: Pan123ApiClient) = CloudProviderFeatures("pan123", { path, body, _, _ ->
            client.featureRequest(path, body ?: JSONObject())
        }, folderLoader = { parent, startPage ->
            // 游标模式与页码模式都支持：有服务端游标就用游标，否则按页码递增
            val result = mutableListOf<CloudRemoteFile>()
            val seen = mutableSetOf<String>()
            val seenCursors = mutableSetOf<String>()
            var page = startPage
            var cursor: String? = null
            var finished = false
            var rounds = 0
            while (!finished) {
                check(rounds++ < 200) { "目录过大，请缩小范围" }
                val listing = client.listFiles(parentFileId = parent, page = page, next = cursor).getOrThrow()
                check(listing.items.all { seen.add(it.fileId) }) { "目录分页重复，请刷新" }
                result.addAll(listing.items.map { CloudRemoteFile(it.fileId, it.fileName, it.isDirectory, it.size, jsonObject("etag" to it.etag, "s3" to it.s3keyFlag).toString(), parent) })
                if (!listing.hasMore) {
                    finished = true
                } else {
                    listing.nextCursor?.let { next ->
                        check(seenCursors.add(next)) { "目录分页重复，请重试" }
                        cursor = next
                    }
                    page += 1
                }
            }
            result to false
        }, legacyShare = Pan123ShareGateway(client), resolvePersonal = { file ->
            // 与浏览器下载链路一致：fileDownloadInfo → HEAD 跟随 → CDN 直链
            client.resolveDownloadUrlViaHead(pan123Item(file)).getOrThrow()
        }, httpClient = client.featureHttpClient)

        fun from(client: Yun139ApiClient) = CloudProviderFeatures("yun139", { path, body, _, _ ->
            client.featureRequest(path, body ?: JSONObject())
        }, folderLoader = { parent, _ ->
            val result = mutableListOf<CloudRemoteFile>()
            var cursor: String? = null
            val cursors = mutableSetOf<String>()
            do {
                val listing = client.listFiles(parent, pageCursor = cursor).getOrThrow()
                result.addAll(listing.items.map { CloudRemoteFile(it.fileId, it.fileName, it.isDir, it.fileSize, parentId = parent) })
                cursor = listing.nextMarker.takeIf { it.isNotBlank() && it != "null" }
                check(cursor == null || cursors.add(cursor)) { "目录分页重复，请重试" }
                check(cursors.size < 200) { "目录过大，请缩小范围" }
            } while (cursor != null)
            result to false
        }, resolvePersonal = { file -> client.getDownloadUrl(file.id, file.name).getOrThrow() }, httpClient = client.featureHttpClient)

        fun from(client: C189ApiClient) = CloudProviderFeatures("cloud189", { path, body, query, _ ->
            val params = body?.let { json -> json.keys().asSequence().associateWith { json.get(it).toString() } } ?: query
            client.featureRequest(path, params, body != null)
        }, folderLoader = { parent, page ->
            val pageSize = 100
            val listing = client.listFiles(folderId = parent, pageNum = page, pageSize = pageSize).getOrThrow()
            // 服务端可能把 pageSize 截断，优先用总数判断是否还有下一页
            val more = if (listing.totalCount > 0) page * pageSize < listing.totalCount else listing.items.size >= pageSize
            listing.items.map { CloudRemoteFile(it.id, it.name, it.isDir, it.size, parentId = parent) } to more
        }, legacyShare = C189ShareGateway(client), resolvePersonal = { file -> client.getDownloadUrl(file.id).getOrThrow() }, httpClient = client.featureHttpClient)
    }

    val rootId: String get() = when (provider) {
        "alipan" -> "root"
        "yun139" -> "/"
        "cloud189" -> "-11"
        else -> "0"
    }

    suspend fun listFolders(parent: String): List<CloudRemoteFile> {
        return listPersonalFiles(parent).filter { it.directory }
    }

    suspend fun listPersonalFiles(parent: String): List<CloudRemoteFile> {
        val result = mutableListOf<CloudRemoteFile>()
        val seen = mutableSetOf<String>()
        for (page in 1..200) {
            val (items, more) = folderLoader(parent, page)
            check(items.all { seen.add(it.id) }) { "目标目录分页重复，请刷新" }
            result.addAll(items)
            if (!more) return result
        }
        error("目录过大，请缩小范围")
    }

    internal suspend fun post(path: String, body: JSONObject): JSONObject = call(path, body, emptyMap(), emptyMap())
    internal suspend fun get(path: String, query: Map<String, String>): JSONObject = call(path, null, query, emptyMap())
    internal fun data(json: JSONObject): JSONObject = json.optJSONObject("data") ?: json

    suspend fun createShare(files: List<CloudRemoteFile>, options: CloudShareOptions): CloudCreatedShare {
        require(files.isNotEmpty()) { "请先选择文件" }
        require(options.expiryDays == null || options.expiryDays > 0) { "分享有效期无效" }
        val ids = files.map { it.id }
        val password = options.password
        return when (provider) {
            "alipan" -> {
                val json = post(
                    "/adrive/v2/share_link/create",
                    jsonObject(
                        "drive_id" to driveId(),
                        "file_id_list" to ids,
                        "share_name" to options.title,
                        "share_pwd" to password,
                        "expiration" to expiration(options.expiryDays),
                    ),
                )
                CloudCreatedShare(json.optString("share_url").ifBlank { "https://www.alipan.com/s/${json.requiredString("share_id")}" }, json.optString("share_pwd", password))
            }
            "quark", "uc" -> {
                require(options.expiryDays in listOf(null, 1, 7, 30)) { "有效期支持 1、7、30 天或永久" }
                val body = jsonObject(
                    "fid_list" to ids,
                    "title" to options.title,
                    "url_type" to if (password.isBlank()) 1 else 2,
                    "expired_type" to when (options.expiryDays) {
                        1 -> 2
                        7 -> 3
                        30 -> 4
                        else -> 1
                    },
                )
                if (password.isNotBlank()) body.put("passcode", password)
                if (provider == "quark") body.put("support_error_code", JSONArray(listOf("41060"))) else body.put("public_search", false)
                val task = pollTask(data(post("share", body)).requiredString("task_id"))
                val shareId = task.requiredString("share_id")
                val share = data(post("share/password", jsonObject("share_id" to shareId)))
                CloudCreatedShare(share.optString("share_url").ifBlank { "https://${if (provider == "uc") "drive.uc.cn" else "pan.quark.cn"}/s/$shareId" }, share.optString("passcode").ifBlank { password })
            }
            "pan123" -> {
                val body = jsonObject(
                    "driveId" to 0, "fileIdList" to ids.map { it.toLong() }, "expiration" to pan123Expiration(options.expiryDays),
                    "shareName" to options.title, "event" to "shareCreate", "fileNum" to files.size,
                    "shareModality" to 4, "trafficLimitSwitch" to 1, "trafficLimit" to 0, "trafficSwitch" to 1, "fillPwdSwitch" to 0,
                )
                if (ids.size == 1) body.put("fileIdList", ids.single().toLong())
                if (password.isNotBlank()) body.put("sharePwd", password)
                val share = data(post("share/create", body))
                val links = share.optJSONObject("shareLinkList")
                val url = links?.optJSONArray("list")?.optString(0).orEmpty().ifBlank { links?.optString("standBy").orEmpty() }
                    .ifBlank { "https://www.123pan.com/s/${share.requiredString("ShareKey")}" }
                CloudCreatedShare(url, password)
            }
            "cloud189" -> {
                require(options.expiryDays in listOf(null, 1, 7)) { "天翼分享有效期支持 1、7 天或永久" }
                require(password.isBlank()) { "天翼访问码由服务器生成，请留空" }
                val params = mapOf("shareType" to "3", "expireTime" to (options.expiryDays ?: 2099).toString())
                val share = if (ids.size == 1) {
                    get("share/createShareLink.action", params + ("fileId" to ids.single())).objects("shareLinkList").firstOrNull() ?: error("天翼未返回分享链接")
                } else {
                    data(post("share/createBatchShare.action", jsonObject("fileIdList" to ids, "shareType" to 3, "expireTime" to (options.expiryDays ?: 2099))))
                }
                CloudCreatedShare(share.requiredString("url"), share.requiredString("accessCode"))
            }
            "yun139" -> {
                require(options.expiryDays in listOf(null, 1, 7, 30)) { "移动分享有效期支持 1、7、30 天或永久" }
                require(password.isBlank()) { "移动提取码由服务器生成，请留空" }
                val req = jsonObject(
                    "subLinkType" to 0, "encrypt" to 1, "coIDLst" to files.filterNot { it.directory }.map { it.id },
                    "caIDLst" to files.filter { it.directory }.map { it.id }, "pubType" to 1, "dedicatedName" to options.title,
                    "periodUnit" to 1, "viewerLst" to emptyList<String>(), "extInfo" to jsonObject("isWatermark" to 0, "shareChannel" to "3001"),
                    "commonAccountInfo" to mobileAccount(),
                )
                options.expiryDays?.let { req.put("period", it) }
                val result = data(post("https://yun.139.com/orchestration/personalCloud-rebuild/outlink/v1.0/getOutLink", jsonObject("getOutLinkReq" to req)))
                val share = result.objects("getOutLinkResSet").firstOrNull()
                    ?: result.optJSONObject("getOutLinkRes")?.objects("getOutLinkResSet")?.firstOrNull() ?: error("移动未返回分享链接")
                CloudCreatedShare(share.optString("linkUrl").ifBlank { share.requiredString("shareUrl") }, share.optString("passwd"))
            }
            else -> error("未知网盘")
        }.also { require(it.url.startsWith("https://") || it.url.startsWith("http://")) { "网盘返回的分享地址无效" } }
    }

    internal suspend fun pollTask(id: String): JSONObject {
        repeat(40) { attempt ->
            val task = data(get("task", mapOf("task_id" to id, "retry_index" to attempt.toString())))
            val status = task.optInt("status", task.optInt("task_status", 0))
            check(task.optInt("status") !in listOf(3, 4) && task.optInt("task_status") !in listOf(3, 4)) { task.optString("message", "网盘任务失败") }
            if (status == 2 || task.optInt("task_status") == 2 || task.optLong("finished_at") > 0) return task
            delay(750)
        }
        error("网盘任务超时，请刷新目标目录确认结果")
    }

    suspend fun openShare(text: String, password: String): CloudShareSession {
        legacyShare?.let { return it.open(text, password) }
        if (provider == "yun139") return mobileOpenShare(text, password)
        val domains = when (provider) {
            "alipan" -> "(?:www\\.)?(?:alipan\\.com|aliyundrive\\.com)"
            "quark" -> "pan\\.quark\\.cn"
            "uc" -> "drive\\.uc\\.cn"
            "yun139" -> "(?:yun\\.139\\.com|caiyun\\.139\\.com)"
            else -> error("请使用当前网盘已有的分享转存入口")
        }
        val id = Regex("https?://$domains/(?:s|t)/([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
            ?: error("请输入当前网盘的有效分享链接")
        val pwd = password.ifBlank { Regex("(?:提取码|访问码|密码|pwd=|password=|passcode=)[:：\\s]*([A-Za-z0-9]+)").find(text)?.groupValues?.get(1).orEmpty() }
        return when (provider) {
            "alipan" -> {
                val json = post("/v2/share_link/get_share_token", jsonObject("share_id" to id, "share_pwd" to pwd))
                CloudShareSession(id, pwd, json.requiredString("share_token"), "root")
            }
            "quark", "uc" -> {
                val body = jsonObject("pwd_id" to id, "passcode" to pwd)
                body.put(if (provider == "quark") "support_visit_limit_private_share" else "share_for_transfer", true)
                val json = data(post("share/sharepage/token", body))
                CloudShareSession(id, pwd, json.requiredString("stoken"), json.optString("first_fid").ifBlank { if (provider == "quark") "" else "0" })
            }
            else -> CloudShareSession(id, pwd, rootId = "root")
        }
    }

    /** 阿里的分享 token 约两小时过期，每次列表/转存前重取（wenxi 同款）；取不到就如实报错，不拿旧 token 掩盖。 */
    private suspend fun alipanShareToken(session: CloudShareSession): String {
        val token = runCatching {
            post("/v2/share_link/get_share_token", jsonObject("share_id" to session.id, "share_pwd" to session.password))
                .requiredString("share_token")
        }.getOrElse { error("阿里未返回分享凭据，请检查提取码或重新打开分享：${it.message}") }
        return token
    }

    suspend fun listShare(session: CloudShareSession, parentId: String): List<CloudRemoteFile> {
        legacyShare?.let { return it.list(session, parentId) }
        val result = mutableListOf<CloudRemoteFile>()
        val seen = mutableSetOf<String>()
        var marker = ""
        // 阿里的分享 token 短命，进入目录前先刷一次
        val shareToken = if (provider == "alipan") alipanShareToken(session) else session.token
        repeat(200) { page ->
            val response = when (provider) {
                "alipan" -> call(
                    "/adrive/v2/file/list_by_share",
                    jsonObject(
                        "share_id" to session.id,
                        "parent_file_id" to parentId,
                        "limit" to 100,
                        "marker" to marker,
                        "order_by" to "name",
                        "order_direction" to "ASC",
                    ),
                    emptyMap(),
                    mapOf("X-Share-Token" to shareToken),
                )
                "quark", "uc" -> {
                    // 夸克与 UC 的分享接口参数不同（wenxi quark_uc.dart 的 list 分流）
                    val quark = provider == "quark"
                    val query = buildMap {
                        put("pwd_id", session.id)
                        put("stoken", session.token)
                        put("pdir_fid", parentId)
                        put("_page", (page + 1).toString())
                        put("_size", if (quark) "100" else "50")
                        put("_fetch_total", "1")
                        if (quark) {
                            put("ver", "2")
                            put("_sort", "file_type:asc,file_name:asc")
                            put("force", "0")
                            put("_fetch_banner", "0")
                            put("_fetch_share", "0")
                            put("fetch_relate_conversation", "0")
                        } else {
                            put("entry", "ft")
                            put("fetch_file_list", "1")
                            put("passcode", "")
                            put("_fetch_task", "1")
                            put("_fetch_share", "1")
                            put("_sort", "")
                        }
                    }
                    data(get(if (quark) "share/sharepage/detail" else "transfer_share/detail", query))
                }
                else -> return mobileListShare(session, parentId)
            }
            val array = if (provider == "alipan") {
                response.objects("items")
            } else {
                check(response.has("list") || response.optJSONObject("detail_info")?.has("list") == true) { "分享文件列表不完整" }
                if (response.has("list")) response.objects("list") else response.getJSONObject("detail_info").objects("list")
            }
            val batch = array.map { file ->
                if (provider == "alipan") {
                    CloudRemoteFile(file.requiredString("file_id"), file.requiredString("name"), file.optString("type") == "folder", file.optLong("size"), parentId = parentId)
                } else {
                    CloudRemoteFile(file.requiredString("fid"), file.optString("file_name", file.optString("fname")), file.optBoolean("dir") || file.optInt("dir") == 1 || file.optInt("isdir") == 1, file.optLong("size", file.optLong("fsize")), file.optString("share_fid_token"), parentId)
                }
            }
            val fresh = batch.filter { seen.add(it.id) }
            result.addAll(fresh)
            if (provider == "alipan") {
                val next = response.optString("next_marker")
                if (next.isBlank()) return result
                check(next != marker && fresh.isNotEmpty()) { "阿里分享分页重复，请刷新重试" }
                marker = next
            } else {
                // 夸克每页 100、UC 每页 50；整页重复说明游标没前进，不能当读完静默返回
                val pageSize = if (provider == "quark") 100 else 50
                if (batch.isEmpty()) return result
                check(fresh.isNotEmpty()) { "分享分页重复，请刷新重试" }
                if (batch.size < pageSize) return result
            }
        }
        error("分享目录过大，请缩小范围")
    }

    suspend fun saveShare(session: CloudShareSession, files: List<CloudRemoteFile>, target: String) {
        require(files.isNotEmpty()) { "请选择文件" }
        legacyShare?.let {
            it.save(session, files, target)
            return
        }
        when (provider) {
            "alipan" -> {
                val shareToken = alipanShareToken(session)
                files.forEach { file ->
                    val response = call(
                        "/v2/file/copy",
                        jsonObject(
                            "share_id" to session.id,
                            "file_id" to file.id,
                            "to_drive_id" to driveId(),
                            "to_parent_file_id" to target,
                            "auto_rename" to true,
                        ),
                        emptyMap(),
                        mapOf("X-Share-Token" to shareToken),
                    )
                    response.optString("async_task_id").takeIf { it.isNotBlank() }?.let { taskId ->
                        var finished = false
                        for (attempt in 0 until 40) {
                            val task = post("/v2/async_task/get", jsonObject("async_task_id" to taskId))
                            val state = task.optString("state").lowercase(Locale.US)
                            check(state !in setOf("failed", "fail", "cancelled")) { "阿里转存任务失败" }
                            if (state in setOf("succeed", "success")) {
                                finished = true
                                break
                            }
                            delay(750)
                        }
                        check(finished) { "阿里转存超时，请刷新目标目录确认" }
                    }
                    check(response.has("file_id") || response.has("async_task_id")) { "阿里未确认转存结果" }
                }
            }
            "quark", "uc" -> {
                require(files.all { it.token.isNotBlank() }) { "分享凭证缺失，请重新打开链接" }
                val task = data(
                    post(
                        "share/sharepage/save",
                        jsonObject(
                            "pwd_id" to session.id,
                            "stoken" to session.token,
                            "pdir_fid" to files.first().parentId,
                            "to_pdir_fid" to target,
                            "fid_list" to files.map { it.id },
                            "fid_token_list" to files.map { it.token },
                            "scene" to "link",
                        ),
                    ),
                )
                pollTask(task.requiredString("task_id"))
            }
            "yun139" -> mobileSaveShare(session, files, target)
        }
    }

    suspend fun upload(parent: String, source: CloudUploadSource) {
        require(source.name.isNotBlank() && source.name.none { it == '/' || it == '\\' || it.code < 32 }) { "文件名称无效" }
        val id = CloudProviderUpload(this, httpClient).upload(parent, source)
        repeat(8) {
            val matches = listPersonalFiles(parent).filter { !it.directory && if (id.isNotBlank()) it.id == id else it.name == source.name }
            check(matches.size <= 1) { "上传目录中出现多个同名文件，请刷新确认" }
            if (matches.size == 1) {
                check(matches.single().size == source.size) { "上传后的文件大小不一致，请检查网盘文件" }
                source.onProgress(source.size, source.size)
                return
            }
            delay(750)
        }
        error("上传已提交，网盘仍在处理，请稍后刷新文件列表确认")
    }

    /** 用户明确选择转存后，从目标目录确认新增文件，再交给播放器。 */
    suspend fun transferAndResolve(session: CloudShareSession, file: CloudRemoteFile, target: String, forPlayback: Boolean = true): Pair<String, String> {
        require(!file.directory) { "请选择文件" }
        val before = listPersonalFiles(target).map { it.id }.toSet()
        saveShare(session, listOf(file), target)
        repeat(10) {
            val matches = listPersonalFiles(target).filter { !it.directory && it.id !in before && (file.size <= 0 || it.size == file.size) }
            if (matches.size == 1) return matches.single().id to if (forPlayback) resolvePersonal(matches.single()) else resolveDownload(matches.single())
            check(matches.size <= 1) { "目标目录同时新增了多个文件，请在网盘中选择转存文件播放" }
            delay(800)
        }
        error("转存已提交，文件暂不可见，请稍后从网盘目录打开")
    }

    suspend fun resolvePersonalFile(id: String, parent: String): String {
        val file = listPersonalFiles(parent).singleOrNull { it.id == id && !it.directory } ?: error("文件已移动或删除，请重新从网盘打开")
        return resolvePersonal(file)
    }

    suspend fun resolvePersonalDownload(file: CloudRemoteFile): String {
        require(!file.directory) { "文件夹不能直接下载" }
        return resolveDownload(file)
    }

    fun fileHeaders(): Map<String, String> = mapOf("Referer" to when (provider) {
        "alipan" -> "https://www.alipan.com/"
        "quark" -> "https://pan.quark.cn/"
        "uc" -> "https://drive.uc.cn/"
        "yun139" -> "https://yun.139.com/"
        "cloud189" -> "https://cloud.189.cn/"
        else -> "https://yun.123pan.cn/"
    }, "User-Agent" to "Mozilla/5.0") + mediaHeaders().filterKeys { !it.equals("Authorization", true) }

    internal fun mobileAccount() = jsonObject("account" to mobileAccountNumber(), "accountType" to 1)

    /**
     * 移动的分享/家庭接口都要带账号。优先用登录时存下的手机号，
     * 否则从 Basic 凭据里解出（格式 `mobile:<账号>:<token>`，与 wenxi 的 accountId 一致）。
     */
    private fun mobileAccountNumber(): String {
        if (Yun139AuthProvider.phoneNumber.isNotBlank()) return Yun139AuthProvider.phoneNumber
        val account = runCatching {
            val raw = Yun139AuthProvider.authorization.removePrefix("Basic").trim()
            // 凭据可能是无 padding 的 Base64（wenxi 用 base64.normalize 补齐），这里同样补齐
            val padded = raw + "=".repeat((4 - raw.length % 4) % 4)
            String(java.util.Base64.getDecoder().decode(padded)).split(':').getOrNull(1).orEmpty()
        }.getOrDefault("")
        check(account.isNotBlank()) { "移动云盘账号信息缺失，请重新登录" }
        return account
    }

    private fun expiration(days: Int?): String {
        if (days == null) return ""
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(System.currentTimeMillis() + days.toLong() * 86_400_000))
    }

    /**
     * 123 的有效期按北京时间解读：写 UTC 时刻 + 8 小时的墙钟时间，再标 +08:00。
     * 这样无论服务端按偏移解析还是按本地时间解析，到期时刻都一致（与 wenxi 相同）。
     */
    private fun pan123Expiration(days: Int?): String {
        if (days == null) return "2099-12-12T08:00:00+08:00"
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(System.currentTimeMillis() + days.toLong() * 86_400_000 + 8 * 3_600_000L)) + "+08:00"
    }
}
