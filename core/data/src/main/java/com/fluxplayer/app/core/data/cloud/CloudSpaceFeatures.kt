package com.fluxplayer.app.core.data.cloud

import com.fluxplayer.app.core.data.yun139.Yun139AuthProvider
import org.json.JSONObject

fun CloudProviderFeatures.spaceFileUri(space: CloudSpace, file: CloudRemoteFile): android.net.Uri =
    android.net.Uri.Builder().scheme("cloud").authority(provider).appendQueryParameter("account", accountId).appendPath(file.id)
        .appendQueryParameter("space", space.id).appendQueryParameter("space_name", space.name)
        .appendQueryParameter("space_parent", file.parentId).appendQueryParameter("space_path", file.token)
        .appendQueryParameter("file_name", file.name).build()

suspend fun CloudProviderFeatures.listSpaces(): List<CloudSpace> = when (provider) {
    "alipan" -> {
        // 相册是可选能力：未开通或接口不可用时给空列表，不要让整个空间入口报错
        runCatching {
            val result = post("/adrive/v1/user/albums_info", JSONObject())
            val id = data(result).requiredString("driveId")
            listOf(CloudSpace(id, "相册"))
        }.getOrElse { emptyList() }
    }
    "cloud189" -> get("family/manage/getFamilyList.action", emptyMap()).objects("familyInfoResp").map {
        CloudSpace(it.requiredString("familyId"), it.optString("remarkName").ifBlank { "家庭云" })
    }
    "yun139" -> {
        val result = mutableListOf<CloudSpace>()
        val seen = mutableSetOf<String>()
        for (page in 1..20) {
            val value = mobileFamily("cloudManage/v1.0/queryFamilyCloud", jsonObject("pageInfo" to jsonObject("pageNum" to page, "pageSize" to 100)))
            val batch = value.objects("familyCloudList")
            batch.forEach { item ->
                val id = item.requiredString("cloudID")
                check(seen.add(id)) { "家庭空间分页重复，请重试" }
                result.add(CloudSpace(id, item.optString("cloudName").ifBlank { "家庭云" }))
            }
            if (batch.size < 100 || result.size == value.optInt("totalCount", -1)) return result
        }
        error("家庭空间数量过多，请在官网整理后重试")
    }
    else -> emptyList()
}

suspend fun CloudProviderFeatures.listSpace(space: CloudSpace, parent: String): List<CloudRemoteFile> {
    val result = mutableListOf<CloudRemoteFile>()
    val seen = mutableSetOf<String>()
    var marker = ""
    repeat(200) { index ->
        val batch = when (provider) {
            "alipan" -> {
                val value = post(
                    "/adrive/v3/file/list",
                    jsonObject(
                        "drive_id" to space.id,
                        "parent_file_id" to parent.ifBlank { "root" },
                        "limit" to 100,
                        "marker" to marker,
                        "order_by" to "name",
                        "order_direction" to "ASC",
                        "fields" to "*",
                    ),
                )
                val next = value.optString("next_marker")
                check(next.isBlank() || next != marker) { "相册分页重复，请刷新" }
                marker = next
                value.objects("items").map { CloudRemoteFile(it.requiredString("file_id"), it.requiredString("name"), it.optString("type") == "folder", it.optLong("size"), parentId = parent) }
            }
            "cloud189" -> {
                val response = get(
                    "family/file/listFiles.action",
                    mapOf(
                        "familyId" to space.id,
                        "folderId" to parent,
                        "pageNum" to (index + 1).toString(),
                        "pageSize" to "100",
                        "iconOption" to "5",
                        "orderBy" to "lastOpTime",
                        "descending" to "true",
                        "mediaType" to "0",
                    ),
                )
                val value = response.getJSONObject("fileListAO")
                value.objects("folderList").map { CloudRemoteFile(it.requiredString("id"), it.requiredString("name"), true, parentId = parent) } +
                    value.objects("fileList").map { CloudRemoteFile(it.requiredString("id"), it.requiredString("name"), false, it.optLong("size"), parentId = parent) }
            }
            "yun139" -> {
                val value = mobileFamily(
                    "content/v1.2/queryContentList",
                    jsonObject(
                        "catalogType" to 3,
                        "cloudID" to space.id,
                        "cloudType" to 1,
                        "catalogID" to parent,
                        "contentSortType" to 0,
                        "sortDirection" to 1,
                        "pageInfo" to jsonObject("pageNum" to index + 1, "pageSize" to 100),
                    ),
                )
                val path = value.requiredString("path")
                check(path.startsWith("root:/") && path.length > 6 && path.substring(6).split('/').all { it.isNotBlank() && it != "." && it != ".." }) { "家庭空间路径无效，请重新选择" }
                check(value.has("cloudCatalogList") || value.has("cloudContentList") || value.optInt("totalCount", -1) == 0) { "家庭空间列表不完整" }
                value.objects("cloudCatalogList").map { CloudRemoteFile(it.requiredString("catalogID"), it.requiredString("catalogName"), true, token = path, parentId = parent) } +
                    value.objects("cloudContentList").map { CloudRemoteFile(it.requiredString("contentID"), it.requiredString("contentName"), false, it.optLong("contentSize"), path, parent) }
            }
            else -> error("当前网盘没有额外空间")
        }
        val fresh = batch.filter { seen.add(it.id) }
        result.addAll(fresh)
        if (provider == "alipan" && marker.isBlank() || provider != "alipan" && batch.size < 100) return result
        check(fresh.isNotEmpty()) { "空间文件分页重复，请刷新" }
    }
    error("空间目录过大，请进入子目录后重试")
}

suspend fun CloudProviderFeatures.resolveSpaceFile(space: CloudSpace, file: CloudRemoteFile): String = when (provider) {
    "alipan" -> post("/v2/file/get_download_url", jsonObject("drive_id" to space.id, "file_id" to file.id)).requiredString("url")
    "cloud189" -> get("family/file/getFileDownloadUrl.action", mapOf("familyId" to space.id, "fileId" to file.id, "type" to "1")).requiredString("fileDownloadUrl").replace("&amp;", "&")
    "yun139" -> mobileFamily(
        "content/v1.0/getFileDownLoadURL",
        jsonObject(
            "catalogType" to 3,
            "cloudID" to space.id,
            "cloudType" to 1,
            "contentID" to file.id,
            "path" to file.token,
        ),
    ).requiredString("downloadURL")
    else -> error("当前网盘没有额外空间")
}

private suspend fun CloudProviderFeatures.mobileFamily(path: String, body: JSONObject): JSONObject {
    val domain = Yun139AuthProvider.userDomainId
    val useDomain = path != "content/v1.0/getFileDownLoadURL" && domain.isNotBlank()
    val account = if (useDomain) jsonObject("userDomainId" to domain, "accountType" to 1) else mobileAccount()
    body.put("commonAccountInfo", account)
    return MobileCloudProtocol.data(MobileCloudProtocol.request("family/$path", body, httpClient))
}
