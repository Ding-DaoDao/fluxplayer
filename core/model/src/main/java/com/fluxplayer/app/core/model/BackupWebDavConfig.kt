package com.fluxplayer.app.core.model

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
    /** 设备名称，用于标识备份来源 */
    val deviceName: String = "",
    /** 打开软件时自动检查是否有新备份 */
    val autoCheckNewBackup: Boolean = false,
    /** 自动备份同步模式：\"both\" / \"local\" / \"remote\" */
    val autoBackupSyncMode: String = "both",
    /** 本地备份路径 */
    val backupPath: String = "",
    /** 仅保留最新备份文件 */
    val keepOnlyLatestBackup: Boolean = true,
    /** 恢复时忽略的配置项列表 */
    val restoreIgnoreList: List<String> = emptyList(),
) {
    val isConfigured: Boolean
        get() = url.isNotBlank()

    val basicAuthHeader: String
        get() = "Basic " + java.util.Base64.getEncoder().encodeToString(
            "$username:$password".toByteArray(),
        )

    /** 构建远程备份文件的完整路径（包含子文件夹前缀） */
    fun remoteBackupPath(fileName: String = "fluxplayer_backup.zip"): String {
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
        val AUTO_SYNC_MODES = listOf("both", "local", "remote")
    }
}
