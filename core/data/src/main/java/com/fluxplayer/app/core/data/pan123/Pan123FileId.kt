package com.fluxplayer.app.core.data.pan123

/** 接口可能返回字符串或整数，直接保留其文本表示，避免转为浮点数丢失精度。 */
internal fun parsePan123FileId(value: Any?): String {
    val id = when (value) {
        is String -> value
        is Number -> value.toString()
        else -> throw IllegalArgumentException("123 云盘返回的文件 ID 无效")
    }
    require(id.isNotBlank()) { "123 云盘返回的文件 ID 为空" }
    return id
}
