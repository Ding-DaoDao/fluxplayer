package com.fluxplayer.app.core.tingshu

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.kittinunf.fuel.json.FuelJson
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FuelJsonAbiTest {
    @Test
    fun externalSourcesReceiveAndroidJsonTypes() {
        assertEquals(JSONObject::class.java, FuelJson::class.java.getMethod("obj").returnType)
        assertEquals(JSONArray::class.java, FuelJson::class.java.getMethod("array").returnType)
        assertEquals("123", FuelJson("""{"FileId":123}""").obj().getString("FileId"))
        assertEquals(1, FuelJson("[1]").array().length())
    }
}
