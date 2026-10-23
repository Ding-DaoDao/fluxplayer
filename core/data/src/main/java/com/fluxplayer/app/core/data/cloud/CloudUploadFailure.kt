package com.fluxplayer.app.core.data.cloud

import okhttp3.Response

/** 仅展示错误码与请求编号，不把响应中的签名、临时密钥和完整地址带入界面。 */
internal fun uploadResponseError(response: Response, text: String): String {
    fun xml(name: String): String? = Regex("<(?:[\\w-]+:)?$name>([^<]*)</(?:[\\w-]+:)?$name>").find(text)?.groupValues?.get(1)
    val json = runCatching { org.json.JSONObject(text) }.getOrNull()
    val code = (xml("Code") ?: json?.optString("code"))?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,80}")) }
    val requestId = (xml("RequestId") ?: response.header("x-oss-request-id") ?: response.header("x-amz-request-id"))
        ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }
    val hint = uploadErrorHint(code)
    return "HTTP ${response.code}${code?.let { "，$it" }.orEmpty()}$hint${requestId?.let { "，请求编号 $it" }.orEmpty()}"
}

/** 业务拒绝与签名、登录错误分别说明；仅凭错误码不推断具体文件是否违规。 */
internal fun uploadErrorHint(code: String?): String = when (code) {
    "InfoSecurityErrorCode", "InfosecuMD5CheckError" -> "，本次上传被天翼安全校验拒绝，具体原因需结合官方客户端结果核实"
    "TextAuditErrorCode" -> "，天翼服务端的文本安全校验未通过，请用官方客户端核实具体原因"
    "SignatureDoesNotMatch" -> "，签名校验失败"
    "RequestTimeTooSkewed", "RequestExpired" -> "，请检查设备时间或重新申请上传地址"
    "ExpiredToken", "InvalidToken" -> "，上传凭证失效"
    "AccessDenied" -> "，上传服务拒绝授权"
    else -> ""
}

internal fun uploadXmlError(text: String): Boolean = Regex("<(?:[\\w-]+:)?Error(?:\\s|>)").containsMatchIn(text)

internal class CloudUploadFailure(message: String, cause: Throwable) : IllegalStateException(message, cause)

internal suspend fun <T> uploadStage(provider: String, stage: String, action: suspend () -> T): T = try {
    action()
} catch (cancelled: kotlinx.coroutines.CancellationException) {
    throw cancelled
} catch (error: CloudUploadFailure) {
    throw error
} catch (error: Exception) {
    val label = when (provider) {
        "pan123" -> "123 云盘"
        "alipan" -> "阿里云盘"
        "quark" -> "夸克云盘"
        "uc" -> "UC 网盘"
        "yun139" -> "移动云盘"
        "cloud189" -> "天翼云盘"
        else -> "网盘"
    }
    throw CloudUploadFailure("$label · $stage：${error.message ?: "请求失败"}", error)
}
