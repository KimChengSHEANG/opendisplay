package com.peetzweg.opendisplay.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTelemetryTest {
    @Test
    fun offsetFromPong_computesRttAndOffset() {
        // t1=1000 phone send, mt=1105 mac, t2=1110 phone recv → rtt=110, offset≈50
        val sample = SessionTelemetry.offsetFromPong(1000.0, 1105.0, 1110.0)!!
        assertEquals(110.0, sample.rtt, 1e-9)
        assertEquals(50.0, sample.offset, 1e-9)
    }

    @Test
    fun offsetFromPong_rejectsHugeRtt() {
        assertNull(SessionTelemetry.offsetFromPong(0.0, 100.0, 5000.0))
    }

    @Test
    fun bestOffset_picksLowestRtt() {
        val samples = listOf(
            SessionTelemetry.OffsetSample(80.0, 10.0),
            SessionTelemetry.OffsetSample(20.0, 42.0),
            SessionTelemetry.OffsetSample(50.0, 99.0),
        )
        assertEquals(42.0, SessionTelemetry.bestOffset(samples)!!, 1e-9)
    }

    @Test
    fun percentile_p50AndP95() {
        val values = listOf(10.0, 20.0, 30.0, 40.0, 50.0)
        assertEquals(30.0, SessionTelemetry.percentile(values, 0.5), 1e-9)
        assertEquals(50.0, SessionTelemetry.percentile(values, 0.95), 1e-9)
        assertEquals(0.0, SessionTelemetry.percentile(emptyList(), 0.5), 1e-9)
    }

    @Test
    fun parseFrameMeta_extractsCapSndAndAnnexB() {
        val json = """{"cap":100.5,"snd":120.0}""".toByteArray()
        val annexB = byteArrayOf(0, 0, 0, 1, 0x65, 0x01)
        val meta = SessionTelemetry.parseFrameMeta(json + annexB)
        assertEquals(100.5, meta.captureMs!!, 1e-9)
        assertEquals(120.0, meta.sendMs!!, 1e-9)
        assertEquals(annexB.toList(), meta.annexB.toList())
    }

    @Test
    fun parseFrameMeta_plainAnnexB_hasNoTimestamps() {
        val annexB = byteArrayOf(0, 0, 0, 1, 0x67, 0x42)
        val meta = SessionTelemetry.parseFrameMeta(annexB)
        assertNull(meta.captureMs)
        assertNull(meta.sendMs)
        assertEquals(annexB.toList(), meta.annexB.toList())
    }

    @Test
    fun inferTransport_loopbackIsUsb() {
        assertEquals("USB", SessionTelemetry.inferTransport("127.0.0.1"))
        assertEquals("USB", SessionTelemetry.inferTransport("::1"))
        assertEquals("WiFi", SessionTelemetry.inferTransport("192.168.1.10"))
    }

    @Test
    fun stampTouch_addsMacClockWhenOffsetKnown() {
        val stamped = SessionTelemetry.stampTouch(
            mapOf("type" to "touch", "phase" to "moved", "x" to 0.5, "y" to 0.5),
            nowMs = 1000.0,
            clockOffsetMs = 50.0,
        )
        assertEquals(1050.0, stamped["t"] as Double, 1e-9)
    }

    @Test
    fun stampTouch_skipsWhenNoOffsetOrAlreadyStamped() {
        val bare = SessionTelemetry.stampTouch(
            mapOf("type" to "touch", "phase" to "began", "x" to 0.0, "y" to 0.0),
            nowMs = 1.0,
            clockOffsetMs = null,
        )
        assertTrue(!bare.containsKey("t"))

        val kept = SessionTelemetry.stampTouch(
            mapOf("type" to "touch", "phase" to "began", "x" to 0.0, "y" to 0.0, "t" to 9.0),
            nowMs = 1.0,
            clockOffsetMs = 50.0,
        )
        assertEquals(9.0, kept["t"])
    }

    @Test
    fun stampTouch_leavesScrollAlone() {
        val scroll = mapOf("type" to "scroll", "dx" to 1.0, "dy" to 2.0)
        assertEquals(scroll, SessionTelemetry.stampTouch(scroll, 1.0, 50.0))
    }
}
