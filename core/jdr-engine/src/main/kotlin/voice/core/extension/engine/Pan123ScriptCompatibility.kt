package voice.core.extension.engine

/** 旧源将下载接口的拒绝访问当作全局登录失效，导致封面、目录和播放互相清除会话。 */
public object Pan123ScriptCompatibility {
    fun script(original: String): String = original.replace(
        Regex("if \\(root.code === 401\\) await clearSavedLogin\\(\\);\\s*throw new Error\\('获取下载地址失败:"),
        "throw new Error('获取下载地址失败:",
    )
}
