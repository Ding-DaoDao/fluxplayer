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
    /** 打开软件时自动检查是否有新备份 */
    val autoCheckNewBackup: Boolean = false,
    /** 自动备份模式：off 关闭，local 保存到授权目录，remote 上传至 WebDAV。 */
    val autoBackupSyncMode: String = "off",
    /** 两次自动备份的最小间隔（分钟），取值来自 [AUTO_BACKUP_INTERVAL_PRESETS]，默认沿用旧版固定的 5 分钟。 */
    val autoBackupIntervalMinutes: Int = DEFAULT_AUTO_BACKUP_INTERVAL_MINUTES,
    /** 自动备份时机：on_stop 退到后台时，on_launch 打开应用后备份一次，on_both 两者都执行。 */
    val autoBackupTrigger: String = AUTO_BACKUP_TRIGGER_ON_STOP,
    /** 最近一次自动备份的时刻，跨进程重启保持间隔，0 表示从未备份过。 */
    val lastAutoBackupAt: Long = 0L,
    /** 兼容旧备份格式，本地导出不再依赖原始文件路径。 */
    val backupPath: String = "",
    /** 本机通过系统目录选择器授予读写权限的持久目录。 */
    val localDirectoryUri: String = "",
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

    /** 间隔可能被旧版本或手工编辑写成非法值，这里收敛到预设区间。 */
    val effectiveAutoBackupIntervalMinutes: Int
        get() = autoBackupIntervalMinutes
            .coerceAtLeast(MIN_AUTO_BACKUP_INTERVAL_MINUTES)
            .coerceAtMost(AUTO_BACKUP_INTERVAL_PRESETS.last())

    fun effectiveAutoBackupIntervalMs(): Long = effectiveAutoBackupIntervalMinutes * 60 * 1000L

    companion object {
        val AUTO_SYNC_MODES = listOf("off", "local", "remote")

        /** 旧版固定 5 分钟节流，既作为间隔预设的下限，也作为默认值保持升级行为不变。 */
        const val MIN_AUTO_BACKUP_INTERVAL_MINUTES = 5
        const val DEFAULT_AUTO_BACKUP_INTERVAL_MINUTES = MIN_AUTO_BACKUP_INTERVAL_MINUTES

        /** 可选的自动备份间隔（分钟）：5 分钟到 12 小时，以及 1 天、2 天、1 周。 */
        val AUTO_BACKUP_INTERVAL_PRESETS = listOf(5, 15, 30, 60, 180, 300, 720, 24 * 60, 2 * 24 * 60, 7 * 24 * 60)

        const val AUTO_BACKUP_TRIGGER_ON_STOP = "on_stop"
        const val AUTO_BACKUP_TRIGGER_ON_LAUNCH = "on_launch"
        const val AUTO_BACKUP_TRIGGER_ON_BOTH = "on_both"
        val AUTO_BACKUP_TRIGGERS = listOf(AUTO_BACKUP_TRIGGER_ON_STOP, AUTO_BACKUP_TRIGGER_ON_LAUNCH, AUTO_BACKUP_TRIGGER_ON_BOTH)

        /** 触发时机是否包含本次调用场景；未知值按退到后台处理，保持旧配置行为。 */
        fun shouldRunOnTrigger(trigger: String, onLaunch: Boolean): Boolean = when (trigger) {
            AUTO_BACKUP_TRIGGER_ON_LAUNCH -> onLaunch
            AUTO_BACKUP_TRIGGER_ON_BOTH -> true
            else -> !onLaunch
        }
    }
}
