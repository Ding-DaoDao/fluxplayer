package com.fluxplayer.app.feature.tingshu

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxplayer.app.core.tingshu.SourceHost
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AliyunWebLoginActivityTest {
    @Before
    @After
    fun clearTestWebStorage() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            WebStorage.getInstance().deleteOrigin("https://www.alipan.com")
        }
    }

    @Test
    fun officialDrivePageReturnsLocalStorageTokenWithoutCookie() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = SourceLoginActivity.intent(context, "阿里云盘测试", "https://www.alipan.com/sign/in", SourceHost.DESKTOP_UA)
        ActivityScenario.launchActivityForResult<SourceLoginActivity>(intent).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val web = requireNotNull(findWebView(activity.window.decorView))
                web.stopLoading()
                web.settings.blockNetworkLoads = true
                // 仅测试登录窗口回传，不请求阿里云盘，也不模拟真实账号验证成功。
                web.loadDataWithBaseURL(
                    "https://www.alipan.com/drive",
                    """<html><script>localStorage.setItem('token',JSON.stringify({access_token:'fixture-access',refresh_token:'fixture-refresh'}));</script></html>""",
                    "text/html",
                    "UTF-8",
                    null,
                )
            }
            val result = scenario.result
            assertEquals(Activity.RESULT_OK, result.resultCode)
            val token = JSONObject(SourceLoginActivity.tokenFromResult(result.resultData))
            assertEquals("fixture-access", token.getString("access_token"))
            assertEquals("fixture-refresh", token.getString("refresh_token"))
        }
    }

    @Test
    fun closingLoginDoesNotReturnCredentials() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = SourceLoginActivity.intent(context, "阿里云盘测试", "https://www.alipan.com/sign/in", SourceHost.DESKTOP_UA)
        ActivityScenario.launchActivityForResult<SourceLoginActivity>(intent).use { scenario ->
            scenario.onActivity { it.finish() }
            assertEquals(Activity.RESULT_CANCELED, scenario.result.resultCode)
            assertTrue(SourceLoginActivity.tokenFromResult(scenario.result.resultData).isEmpty())
        }
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findWebView(view.getChildAt(i))?.let { return it }
        }
        return null
    }
}
