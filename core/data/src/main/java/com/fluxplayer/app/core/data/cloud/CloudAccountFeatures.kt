package com.fluxplayer.app.core.data.cloud

import com.fluxplayer.app.core.data.yun139.Yun139AuthProvider
import org.json.JSONObject

data class CloudAccountUsage(val name: String, val usedBytes: Long, val totalBytes: Long)

suspend fun CloudProviderFeatures.accountUsage(): CloudAccountUsage = when (provider) {
    "alipan" -> {
        val space = post("/v2/databox/get_personal_info", JSONObject()).getJSONObject("personal_space_info")
        CloudAccountUsage("阿里云盘", space.getLong("used_size"), space.getLong("total_size"))
    }
    "quark", "uc" -> {
        val value = data(get("member", mapOf("fetch_subscribe" to "true", "_ch" to "home")))
        CloudAccountUsage(value.optString("nickname").ifBlank { value.optString("username").ifBlank { "网盘账号" } }, value.getLong("use_capacity"), value.getLong("total_capacity"))
    }
    "yun139" -> {
        val value = data(post("https://user-njs.yun.139.com/user/disk/quota/detail", jsonObject("userDomainId" to Yun139AuthProvider.userDomainId)))
        val disk = value.optJSONObject("diskInfo") ?: value
        val total = disk.getDouble("diskSize")
        val used = when {
            disk.has("freeDiskSize") -> total - disk.getDouble("freeDiskSize")
            disk.has("usedSize") -> disk.getDouble("usedSize")
            disk.has("quotaList") -> disk.objects("quotaList").sumOf { it.getDouble("usedSize") }
            else -> error("移动未返回已用容量，请重试")
        }
        check(total > 0 && used >= 0 && used <= total) { "移动返回的容量信息无效" }
        CloudAccountUsage("移动云盘", (used * 1024 * 1024).toLong(), (total * 1024 * 1024).toLong())
    }
    else -> error("请使用当前网盘菜单中的账号信息入口")
}.also { check(it.usedBytes >= 0 && it.totalBytes > 0) { "网盘返回的容量信息无效" } }
