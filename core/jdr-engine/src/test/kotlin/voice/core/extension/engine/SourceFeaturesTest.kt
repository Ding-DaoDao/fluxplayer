package voice.core.extension.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SourceFeaturesTest {
    @Test
    fun settingsLoginStateAndCacheSurviveEngineRecreation() = runTest {
        val state = MemorySourceHost()
        val host = object : SourceHostBridge by state {
            override fun settings(): String = """{"username":"tester","root":"books","enabled":true}"""
        }
        val script = """
            registerSource({id:'features',
              async login(p) {
                if (p.action === 'login') {
                  host.storage.set('session', {user:host.settings.get('username'), token:'keep'});
                  host.cache.set('metadata', {value:7}, 60);
                  host.cache.set('expired', 7, 0.000001);
                }
                if (p.action === 'logout') host.storage.remove('session');
                return {authenticated:!!host.storage.get('session'), message:JSON.stringify(host.cache.get('metadata'))};
              },
              async browse() { return {items:[{id:host.settings.get('root'),name:'书籍',type:'directory'}],nextPage:2}; },
              async search() { return [{id:'one',bookTitle:String(host.settings.get('enabled'))+':'+String(host.cache.get('expired'))}]; }
            });
        """.trimIndent()
        val http = SandboxHttp { _, _, _ -> error("should not call network") }
        JsSourceEngine.create("features", script, "features.js", http, host = host).use { engine ->
            assertTrue(SourceFeatures.parseLogin(engine.invoke("login", """{"action":"login"}""", 5000)).authenticated!!)
        }
        JsSourceEngine.create("features", script, "features.js", http, host = host).use { engine ->
            val login = SourceFeatures.parseLogin(engine.invoke("login", """{"action":"status"}""", 5000))
            assertEquals(true, login.authenticated)
            assertEquals("{\"value\":7}", login.message)
            val directory = SourceFeatures.parseDirectory(engine.invoke("browse", "{}", 5000), 1)
            assertEquals("books", directory.items.single().id)
            assertEquals(2, directory.nextPage)
            assertEquals("true:null", SourceContract.parseSearchResults(engine.invoke("search", "{}", 5000)).single().title)
            assertEquals(false, SourceFeatures.parseLogin(engine.invoke("login", """{"action":"logout"}""", 5000)).authenticated)
        }
        assertNull(state.get("session"))
    }

    @Test
    fun validatesLoginAndDirectoryResults() {
        val pending = SourceFeatures.parseLogin("""{"webUrl":"https://example.com/login","qrImage":"https://example.com/qr.png","state":{"session":"s"}}""")
        assertEquals("https://example.com/login", pending.cookieUrl)
        assertEquals("s", pending.state["session"]?.toString()?.trim('"'))
        assertFailsWith<IllegalArgumentException> { SourceFeatures.parseLogin("""{"webUrl":"file:///private"}""") }
        assertFailsWith<IllegalArgumentException> { SourceFeatures.parseLogin("""{"authenticated":"yes"}""") }
        assertFailsWith<IllegalArgumentException> { SourceFeatures.parseLogin("""{"webUrl":"https://example.com/login","cookieUrl":"https://unrelated.com/"}""") }
        assertFailsWith<IllegalArgumentException> { SourceFeatures.parseDirectory("""{"items":[],"nextPage":1}""", 1) }
        assertFailsWith<IllegalArgumentException> { SourceFeatures.parseDirectory("""{"items":[{"id":"a","name":"b","type":"file"}]}""", 1) }
    }

    @Test
    fun rejectsInvalidConfigDeclarations() {
        assertFailsWith<IllegalArgumentException> { SourceFeatures.validateSettings(listOf(SourceSetting("root", "路径", "directory")), false) }
        assertFailsWith<IllegalArgumentException> { SourceFeatures.validateSettings(listOf(SourceSetting("x", "X"), SourceSetting("x", "X2")), true) }
    }
}
