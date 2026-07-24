package com.peetzweg.opendisplay.wire

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameCodecTest {
    @Test
    fun roundTrip_singleFrame() {
        val payload = """{"type":"ping"}""".toByteArray()
        val framed = FrameCodec.encode(payload)
        val deframer = FrameCodec.Deframer()
        val out = deframer.push(framed)
        assertEquals(1, out.size)
        assertArrayEquals(payload, out[0])
    }

    @Test
    fun protocolVersion_matchesIos() {
        assertEquals(2, WireProtocol.version)
        assertEquals("hostSleeping", WireMessage.hostSleeping)
    }
}
