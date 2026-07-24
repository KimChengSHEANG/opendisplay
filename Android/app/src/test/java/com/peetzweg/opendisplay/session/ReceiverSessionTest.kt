package com.peetzweg.opendisplay.session

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiverSessionTest {
    @Test
    fun helloJson_containsRequiredKeys() {
        val json = ReceiverSession.helloJson(
            wide = 1170, high = 2532, scale = 3.0,
            device = "Android", id = "abc", pv = 2
        )
        val obj = JSONObject(json)
        assertEquals("hello", obj.getString("type"))
        assertEquals(1170, obj.getInt("pixelsWide"))
        assertEquals(2532, obj.getInt("pixelsHigh"))
        assertEquals(3.0, obj.getDouble("scale"), 0.0001)
        assertEquals("Android", obj.getString("device"))
        assertEquals("abc", obj.getString("id"))
        assertEquals(2, obj.getInt("pv"))
    }

    @Test
    fun helloJson_chromebookDevice() {
        val json = ReceiverSession.helloJson(
            wide = 1920, high = 1080, scale = 1.0,
            device = "Chromebook", id = "xyz", pv = 2
        )
        val obj = JSONObject(json)
        assertEquals("Chromebook", obj.getString("device"))
    }
}
