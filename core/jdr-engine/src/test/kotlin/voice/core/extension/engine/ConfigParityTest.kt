package voice.core.extension.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ConfigParityTest {
    @Test
    fun manifestButtonsCanUseConfigActionAndCoverIsDiscovered() = runTest {
        val script = """
            registerSource({id:'hooks',
              configAction(p){return {message:p.action};},
              cover(p){return {url:'https://example.com/'+p.bookId+'.jpg',headers:{Referer:'https://example.com/'}};}
            });
        """.trimIndent()
        val engine = JsSourceEngine.create("hooks", script, "hooks.js", SandboxHttp { _, _, _ -> error("不应请求网络") })
        try {
            assertTrue("config" in engine.features())
            assertTrue("cover" in engine.features())
            assertEquals(emptyList(), SourceFeatures.parseSettings(engine.invoke("config", """{"action":"get"}""", 1000), false))
            assertEquals("""{"message":"btn_help"}""", engine.invoke("config", """{"action":"btn_help"}""", 1000))
            val cover = SourceContract.parseAudio(engine.invoke("cover", """{"bookId":"D_42"}""", 1000))
            assertEquals("https://example.com/D_42.jpg", cover.url)
            assertEquals("https://example.com/", cover.headers["Referer"])
        } finally {
            engine.close()
        }
    }

    @Test
    fun settingsArrayButtonsKeepTheirCallback() = runTest {
        val engine = JsSourceEngine.create(
            "settingsbutton",
            """registerSource({id:'settingsbutton',settings:[{type:'Button',key:'help',label:'说明',click(){return '说明内容';}}]});""",
            "settings.js",
            SandboxHttp { _, _, _ -> error("不应请求网络") },
        )
        try {
            assertEquals("\"说明内容\"", engine.invoke("config", """{"action":"help"}""", 1000))
        } finally {
            engine.close()
        }
    }

    @Test
    fun jarStyleConfigurationButtonsAndCredentialsWorkWithoutManifestSettings() = runTest {
        val host = MemorySourceHost()
        val source = """
            registerSource({id:'configtest',
              getCustomConfigItems() { return [
                {type:'Text',key:'cookie',label:'Cookie',default:ExternalSourcePrefs.getString('configtest.cookie','')},
                {type:'Switch',key:'enabled',label:'启用',default:true},
                {type:'MultiSelect',key:'formats',label:'格式',options:['mp3','m4a'],default:['mp3']},
                {type:'Button',label:'登录',click(){ExternalSourcePrefs.putString('configtest.cookie','new-session');}}
              ]; },
              reset(){host.storage.set('reset',true);},
              search(){return [{id:'one',bookTitle:ExternalSourcePrefs.getString('configtest.cookie','missing')}];}
            });
        """.trimIndent()
        val engine = JsSourceEngine.create("configtest", source, "config.js", SandboxHttp { _, _, _ -> error("不应请求网络") }, host = host)
        try {
            assertTrue("config" in engine.features())
            val fields = SourceFeatures.parseSettings(engine.invoke("config", """{"action":"get"}""", 1000), false)
            assertEquals(listOf("text", "switch", "multiselect", "button"), fields.map { it.type })
            assertEquals("true", fields[1].default)
            assertEquals("mp3", fields[2].default)
            engine.invoke("config", """{"action":"save","values":{}}""", 1000)
            assertEquals("true", host.get("reset"))
            engine.invoke("config", """{"action":"action3"}""", 1000)
            assertEquals("new-session", SourceContract.parseSearchResults(engine.invoke("search", "{}", 1000)).single().title)
            assertEquals("new-session", SourceFeatures.parseSettings(engine.invoke("config", """{"action":"get"}""", 1000), false).first().default)
        } finally {
            engine.close()
        }
    }
}
