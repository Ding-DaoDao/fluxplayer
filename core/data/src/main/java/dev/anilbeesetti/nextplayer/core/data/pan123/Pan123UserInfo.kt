package dev.anilbeesetti.nextplayer.core.data.pan123

data class Pan123UserInfo(
    val nickname: String,
    val uid: Long,
    val spaceUsed: Long,
    val spacePermanent: Long,
    val isVip: Boolean,
    val headImage: String,
    val vipDesc: String,
    val vipTimeDesc: String
)
