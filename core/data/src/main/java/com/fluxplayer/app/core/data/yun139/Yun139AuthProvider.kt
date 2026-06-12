package com.fluxplayer.app.core.data.yun139

import java.security.SecureRandom

object Yun139AuthProvider {
    @Volatile var authorization: String = ""
    @Volatile var phoneNumber: String = ""
    @Volatile var userDomainId: String = ""
    @Volatile var isActive: Boolean = false

    val deviceInfo: String by lazy {
        generateDeviceInfo()
    }

    private fun generateDeviceInfo(): String {
        val uuid = randomHex(32)
        val deviceType = DEVICE_TYPES.random()
        val osVersion = OS_VERSIONS.random()
        return "1|127.0.0.1|1|12.4.1|Xiaomi|$deviceType|$uuid|02-00-00-00-00-00|$osVersion|1220X2712|zh||||0000|0|"
    }

    private fun randomHex(length: Int): String {
        val charset = "0123456789ABCDEFG"
        return (1..length).map {
            charset[SecureRandom().nextInt(charset.length)]
        }.joinToString("")
    }

    fun clear() {
        authorization = ""
        isActive = false
    }

    fun getPlayHeaders(): Map<String, String> {
        return if (isActive) {
            mapOf(
                "Authorization" to authorization,
                "x-yun-device-id" to deviceInfo,
                "x-yun-client-info" to deviceInfo,
                "x-yun-api-version" to "v2",
                "x-yun-svc-type" to "1",
                "x-yun-module-type" to "100",
                "x-yun-app-channel" to "10000023"
            )
        } else emptyMap()
    }

    private val DEVICE_TYPES = listOf(
        "2312DRAABC", "2312DR AABI", "2312DR AABG",
        "2310RK86C", "2310RK86I", "24122RKC7C",
        "24127RK2CC", "24108PN61G", "24108PN61I"
    )
    private val OS_VERSIONS = listOf(
        "android 13", "android 14", "android 15", "android 16"
    )
}
