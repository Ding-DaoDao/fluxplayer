package com.fluxplayer.app.core.model

import java.net.URI
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.serialization.Serializable

/**
 * 备份专用的 WebDAV 服务器配置（独立于浏览用 WebDAV 列表）。
 * 同时存储备份行为偏好。
 */
@Serializable
data class BackupWebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    /** WebDAV 服务器上的子文件夹，备份文件将存放于此 */
    val subfolder: String = "",
    /** 设备名称，用于在备份文件名中标识来源（留空则不添加） */
    val deviceName: String = "",
    /**
     * 备份归档的加密密码（PBKDF2 + AES-GCM，见 core:data 的 BackupCrypto）。
     *
     * 归档里含 123 明文账号密码与各平台 Cookie，因此导出/上传一律加密，恢复时需要同一密码。
     * 自动备份（[autoBackupSyncMode]）与远程恢复在后台运行、无法弹窗，所以密码必须随配置保存；
     * 这是"可用性"与"不落盘"之间取的折中——配置本身只在应用私有目录，仍显著优于明文 ZIP。
     */
    val backupPassphrase: String = "",
    /** 打开软件时自动检查是否有新备份 */
    val autoCheckNewBackup: Boolean = false,
    /** 自动备份模式：off 关闭，remote 在应用退到后台时上传至 WebDAV。 */
    val autoBackupSyncMode: String = "off",
    /** 兼容旧备份格式，本地导出不再依赖原始文件路径。 */
    val backupPath: String = "",
    /** 兼容旧备份格式，不再自动删除旧备份。 */
    val keepOnlyLatestBackup: Boolean = false,
    /** 恢复时忽略的配置项列表 */
    val restoreIgnoreList: List<String> = emptyList(),
    /** 标记：服务器上检测到新备份，需要提示用户恢复 */
    val hasPendingNewBackup: Boolean = false,
    /** 最近已经检查或上传过的远程备份，用于避免重复提示。 */
    val lastSeenRemoteBackup: String = "",
) {
    val isConfigured: Boolean
        get() = runCatching {
            val uri = URI(url)
            uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.query == null && uri.fragment == null
        }.getOrDefault(false)

    val basicAuthHeader: String
        get() = "Basic " + java.util.Base64.getEncoder().encodeToString(
            "$username:$password".toByteArray(),
        )

    /** 生成带时间戳的备份文件名，格式：fluxplayer_backup_2025-06-14_09-44-30.zip */
    fun generateBackupFileName(): String {
        val timestamp = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss_SSS")) + "_" + UUID.randomUUID().toString().take(8)
        val device = deviceName.trim().replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(40)
        return if (device.isEmpty()) {
            "fluxplayer_backup_$timestamp.zip"
        } else {
            "fluxplayer_backup_${device}_$timestamp.zip"
        }
    }

    /** 构建远程备份文件的完整路径（包含子文件夹前缀） */
    fun remoteBackupPath(fileName: String): String {
        val folder = subfolder.trim('/')
        return if (folder.isEmpty()) "/$fileName" else "/$folder/$fileName"
    }

    /** 构建远程备份目录路径（用于 listDirectory） */
    fun remoteBackupDir(): String {
        val folder = subfolder.trim('/')
        return if (folder.isEmpty()) "/" else "/$folder/"
    }

    fun toWebDavServer(): WebDavServer = WebDavServer(
        id = "backup",
        name = "备份服务器",
        url = url,
        username = username,
        password = password,
    )

    companion object {
        val AUTO_SYNC_MODES = listOf("off", "remote")
    }
}
