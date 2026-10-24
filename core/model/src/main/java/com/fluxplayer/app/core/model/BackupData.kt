package com.fluxplayer.app.core.model

import kotlinx.serialization.Serializable

/**
 * 备份/恢复数据包，包含用户设置、播放器设置、WebDAV 服务器、OpenList 配置和云盘凭证。
 * 包含听书目录与进度，普通视频历史记录不包含在内。
 */
@Serializable
data class BackupData(
    val version: Int = CURRENT_VERSION,
    val backupTime: Long = System.currentTimeMillis(),
    val appPreferences: ApplicationPreferences,
    val playerPreferences: PlayerPreferences,
    val webDavServers: WebDavServers,
    val openListConfig: OpenListBackupConfig? = null,
    val alipanConfig: AlipanBackupConfig? = null,
    val cloud189Config: Cloud189BackupConfig? = null,
    val pan123Config: Pan123BackupConfig? = null,
    val quarkConfig: QuarkBackupConfig? = null,
    val yun139Config: Yun139BackupConfig? = null,
    val backupWebDavConfig: BackupWebDavConfig? = null,
    val onlineListeningLibrary: String? = null,
    val cloudAccounts: String? = null,
) {
    companion object {
        const val CURRENT_VERSION = 3
    }
}

@Serializable
data class OpenListBackupConfig(
    val autoStart: Boolean = false,
    val adminPassword: String? = null,
)

@Serializable
data class AlipanBackupConfig(
    val authorization: String? = null,
    val driveId: String? = null,
    val refreshToken: String? = null,
    val deviceId: String? = null,
    val signature: String? = null,
    val cookies: String? = null,
    val devicePrivateKey: String? = null,
    val expiresAt: Long = 0,
    val userId: String? = null,
    val deviceSessionExpiresAt: Long = 0,
)

@Serializable
data class Cloud189BackupConfig(
    val accessToken: String? = null,
    val sessionKey: String? = null,
    val sessionSecret: String? = null,
    val refreshToken: String? = null,
    val expiresIn: Long = 0L,
    val cookies: String? = null,
    val username: String? = null,
    val password: String? = null,
    val autoLoginBlocked: Boolean = false,
)

@Serializable
data class Pan123BackupConfig(
    val passport: String? = null,
    val password: String? = null,
    val token: String? = null,
    val loginUuid: String? = null,
    val refreshTokenExpireTime: Long = 0,
    val autoLoginBlocked: Boolean = false,
    val userId: String? = null,
)

@Serializable
data class QuarkBackupConfig(
    val quarkCookie: String? = null,
    val ucCookie: String? = null,
    val quarkRefreshedAt: Long = 0,
    val ucRefreshedAt: Long = 0,
    val tvAccessToken: String? = null,
    val tvRefreshToken: String? = null,
    val tvOwner: String? = null,
    val tvDevice: String? = null,
    val tvExpiresAt: Long = 0,
    val tvGeneration: String? = null,
)

@Serializable
data class Yun139BackupConfig(
    val authorization: String? = null,
    val phoneNumber: String? = null,
    val userDomainId: String? = null,
    val lastRefresh: Long = 0L,
    val cookies: String? = null,
)
