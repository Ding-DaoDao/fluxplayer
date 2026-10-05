package voice.core.extension.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ConfigParityTest {
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
