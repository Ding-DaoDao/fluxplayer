package com.fluxplayer.app.core.tingshu

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.TimeoutCancellationException

/** 将书源和播放错误整理为可操作的提示，详情中隐藏账号凭证及签名链接。 */
object ListeningErrors {
    fun describe(error: Throwable, action: String, httpStatus: Int? = null): String {
        val causes = generateSequence(error) { it.cause }.take(12).toList()
        val detail = sanitize(causes.lastOrNull { !it.message.isNullOrBlank() }?.message ?: error.javaClass.simpleName)
        val status = httpStatus ?: Regex("(?:HTTP|code|Response code)\\s*[=:]?\\s*(\\d{3})(?!\\d)", RegexOption.IGNORE_CASE).find(detail)?.groupValues?.get(1)?.toIntOrNull()
        val reason = when {
            causes.any { it is SocketTimeoutException || it is TimeoutCancellationException } || detail.contains("timeout", true) || detail.contains("timed out", true) -> "请求超时，请检查网络后重试；章节较多的书籍可能需要更长时间。"
            causes.any { it is UnknownHostException || it is ConnectException } -> "无法连接服务器，请检查网络后重试。"
            causes.any { it is SSLException } -> "安全连接失败，请检查网络和设备时间后重试。"
            status == 401 -> "账号凭证已失效，请在书源配置中更新凭证。"
            status == 403 -> "服务器拒绝访问，可能是账号权限不足或播放链接已失效，请重试或检查书源配置。"
            status == 404 -> "文件或播放地址已不存在，请刷新书籍目录后重试。"
            status == 429 -> "请求过于频繁，请稍等片刻后重试。"
            status != null && status >= 500 -> "网盘服务器暂时不可用，请稍后重试。"
            detail.contains("没有找到音频") || detail.contains("没有可播放章节") -> "这个目录中没有可播放的音频，请选择包含音频章节的书籍文件夹。"
            detail.contains("未配置凭证") -> "书源未读取到账号密码或 Token，请打开书源配置并保存后重试。"
            detail.contains("未登录") || detail.contains("凭证") -> "账号凭证不可用，请检查书源配置。"
            else -> "请重试；如果仍然失败，可查看错误详情并检查书源配置。"
        }
        return "$action：$reason\n$detail"
    }

    fun sanitize(message: String): String = message
        .replace(Regex("https?://[^\\s\"'<>]+"), "[链接已隐藏]")
        .replace(Regex("(?i)[\"']?(authorization|cookie|password|token|passport|sessionKey|__puus|__puuid|COOKIE_LOGIN_USER)[\"']?\\s*[=:]\\s*(?:\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|[^\\r\\n,}]+)"), "$1=[已隐藏]")
        .take(2000)
}
