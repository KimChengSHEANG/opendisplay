package com.peetzweg.opendisplay.session

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun isVideoFrame_plainAnnexBWithoutBraceIsVideo() {
        val payload = byteArrayOf(0, 0, 0, 1, 0x67, 0x42)
        assertTrue(ReceiverSession.isVideoFrame(payload))
    }

    @Test
    fun isVideoFrame_pureControlJsonIsNotVideo() {
        val payload = """{"type":"ping","t":1}""".toByteArray()
        assertFalse(ReceiverSession.isVideoFrame(payload))
    }

    @Test
    fun isVideoFrame_telemetryPrefixedAnnexBIsVideo() {
        val json = """{"cap":100,"snd":101}""".toByteArray()
        val annexB = byteArrayOf(0, 0, 0, 1, 0x65, 0x01)
        assertTrue(ReceiverSession.isVideoFrame(json + annexB))
    }

    @Test
    fun stripTelemetryPrefix_removesJsonHeaderAheadOfStartCode() {
        val json = """{"cap":100,"snd":101}""".toByteArray()
        val annexB = byteArrayOf(0, 0, 0, 1, 0x65, 0x01)
        assertArrayEquals(annexB, ReceiverSession.stripTelemetryPrefix(json + annexB))
    }

    @Test
    fun stripTelemetryPrefix_leavesPlainAnnexBUnchanged() {
        val annexB = byteArrayOf(0, 0, 0, 1, 0x67, 0x42)
        assertArrayEquals(annexB, ReceiverSession.stripTelemetryPrefix(annexB))
    }

    private val noopListener = object : ReceiverSession.Listener {
        override fun onConnected() {}
        override fun onDisconnected() {}
        override fun onVideoFrame(data: ByteArray) {}
        override fun onControl(map: Map<String, Any>) {}
        override fun onStatus(status: String) {}
    }

    @Test
    fun updatePanel_updatesFieldsForNextHelloWithNoLiveConnection() {
        val session = ReceiverSession(listener = noopListener)
        session.pixelsWide = 1080
        session.pixelsHigh = 2400

        session.updatePanel(2400, 1080, 3.0)

        assertEquals(2400, session.pixelsWide)
        assertEquals(1080, session.pixelsHigh)
        assertEquals(3.0, session.scale, 0.0001)
    }

    @Test
    fun updatePanel_sameMetricsIsNoOp() {
        val session = ReceiverSession(listener = noopListener)
        session.pixelsWide = 1080
        session.pixelsHigh = 2400
        session.scale = 2.0

        session.updatePanel(1080, 2400, 2.0)

        assertEquals(1080, session.pixelsWide)
        assertEquals(2400, session.pixelsHigh)
    }
}
