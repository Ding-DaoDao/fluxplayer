package voice.core.extension.engine

import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable
public data class SourceSetting(
    val key: String,
    val label: String,
    val type: String = "text",
    val default: String = "",
    val options: List<String> = emptyList(),
    val action: String = "",
    val hint: String = "",
)

/** Host implementations scope every call to the package and source. */
public interface SourceHostBridge {
    public fun putSetting(key: String, value: String?) {
        error("宿主不支持修改配置")
    }
    public fun settings(): String
    public fun get(key: String): String?
    public fun set(key: String, value: String)
    public fun remove(key: String)
    public fun clear()
}

public class MemorySourceHost : SourceHostBridge {
    private val values = ConcurrentHashMap<String, String>()
    private val settings = ConcurrentHashMap<String, String>()
    override fun settings(): String = JsonObject(settings.mapValues { JsonPrimitive(it.value) }).toString()
    override fun putSetting(key: String, value: String?) {
        if (value == null) settings.remove(key) else settings[key] = value
    }
    override fun get(key: String): String? = values[key]
    override fun set(key: String, value: String) {
        values[key] = value
    }
    override fun remove(key: String) {
        values.remove(key)
    }
    override fun clear() {
        values.clear()
    }
}

public data class SourceLogin(
    val authenticated: Boolean? = null,
    val message: String = "",
    val webUrl: String = "",
    val cookieUrl: String = "",
    val qrImage: String = "",
    /**
     * 登录页是否需要桌面 UA。部分网盘（夸克、天翼）的移动版登录页功能残缺，
     * 必须用桌面 UA 才能正常出登录表单。对应 JAR 源的 `ILogin.isLoginDesktop()`。
     */
    val desktopUserAgent: Boolean = false,
    val state: JsonObject = JsonObject(emptyMap()),
)

public data class DirectoryItem(val id: String, val name: String, val directory: Boolean, val extra: JsonObject)
public data class DirectoryPage(val items: List<DirectoryItem>, val nextPage: Int?)

public object SourceFeatures {
    public fun validateSettings(fields: List<SourceSetting>, canBrowse: Boolean) {
        require(fields.size <= 32) { "书源配置项不能超过 32 项" }
        require(fields.map { it.key }.distinct().size == fields.size) { "书源配置键重复" }
        fields.forEach {
            require(it.key.matches(Regex("[a-zA-Z][a-zA-Z0-9_.-]{0,63}")) && it.label.isNotBlank()) { "无效的书源配置项" }
            require(it.type in setOf("text", "password", "switch", "select", "directory", "multiselect", "button")) { "未知配置类型 ${it.type}" }
            require(it.type != "directory" || canBrowse) { "目录设置需要 browse 能力" }
            require(it.type !in setOf("select", "multiselect") || it.options.isNotEmpty()) { "选择项不能为空" }
        }
    }

    /** Accept the familiar JAR configuration shape from JavaScript hooks. */
    public fun parseSettings(raw: String, canBrowse: Boolean): List<SourceSetting> {
        val result = Json.parseToJsonElement(raw)
        val items = if (result is JsonObject) result["items"] else result
        val array = items as? JsonArray ?: throw SourceContractException("配置必须返回数组或 items 数组")
        val fields = array.mapIndexed { index, element ->
            val obj = element as? JsonObject ?: throw SourceContractException("配置项必须是对象")
            val type = obj.text("type").lowercase().ifBlank { "text" }
            val default = when (val value = obj["default"]) {
                is JsonArray -> value.joinToString(",") { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
                is JsonPrimitive -> value.contentOrNull.orEmpty()
                else -> ""
            }
            SourceSetting(
                key = obj.text("key").ifBlank { if (type == "button") "action$index" else "" },
                label = obj.text("label"),
                type = type,
                default = default,
                options = (obj["options"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull ?: error("选项必须是字符串") }.orEmpty(),
                action = obj.text("action").ifBlank { obj.text("key").ifBlank { "action$index" } },
                hint = obj.text("hint"),
            )
        }
        validateSettings(fields, canBrowse)
        return fields
    }

    public fun parseLogin(raw: String): SourceLogin {
        val obj = Json.parseToJsonElement(raw) as? JsonObject ?: throw SourceContractException("login 必须返回对象")
        val web = obj.text("webUrl")
        val cookie = obj.text("cookieUrl").ifBlank { web }
        val qr = obj.text("qrImage")
        listOf(web, cookie).filter { it.isNotBlank() }.forEach { requireHttp(it) }
        if (web.isNotBlank() && cookie.isNotBlank()) {
            val webHost = web.toHttpUrlOrNull()!!
            val cookieHost = cookie.toHttpUrlOrNull()!!
            require((webHost.topPrivateDomain() ?: webHost.host) == (cookieHost.topPrivateDomain() ?: cookieHost.host)) { "Cookie 地址必须属于登录网站" }
        }
        if (qr.isNotBlank() && !qr.startsWith("data:image/")) requireHttp(qr)
        require(qr.length <= 350_000) { "二维码图片过大" }
        val auth = obj["authenticated"]
        require(auth == null || (auth is JsonPrimitive && auth.booleanOrNull != null)) { "authenticated 必须是布尔值" }
        val desktop = (obj["desktopUserAgent"] as? JsonPrimitive)?.booleanOrNull ?: false
        return SourceLogin(
            (auth as? JsonPrimitive)?.booleanOrNull,
            obj.text("message"),
            web,
            cookie,
            qr,
            desktop,
            obj["state"] as? JsonObject ?: JsonObject(emptyMap()),
        )
    }

    public fun parseDirectory(raw: String, currentPage: Int): DirectoryPage {
        val obj = Json.parseToJsonElement(raw) as? JsonObject ?: throw SourceContractException("browse 必须返回对象")
        val items = obj["items"] as? JsonArray ?: throw SourceContractException("browse.items 必须是数组")
        val mapped = items.map { item ->
            val data = item as? JsonObject ?: throw SourceContractException("目录项必须是对象")
            val id = data.text("id")
            val name = data.text("name").ifBlank { data.text("bookTitle") }
            val type = data.text("type")
            require(id.isNotBlank() && name.isNotBlank() && type in setOf("directory", "book")) { "目录项需要 id/name/type" }
            DirectoryItem(id, name, type == "directory", data)
        }
        val next = (obj["nextPage"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
        require(next == null || next > currentPage) { "下一页必须大于当前页" }
        require(mapped.map { it.id }.distinct().size == mapped.size) { "目录项 ID 重复" }
        return DirectoryPage(mapped, next)
    }

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun requireHttp(url: String) {
        require(url.toHttpUrlOrNull() != null) { "登录地址必须为 HTTP(S)" }
    }
}
