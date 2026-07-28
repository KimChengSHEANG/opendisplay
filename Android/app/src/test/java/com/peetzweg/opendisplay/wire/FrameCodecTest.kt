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
    fun roundTrip_multiKilobytePayload_splitAcrossPushes() {
        // A multi-KB payload delivered in awkward chunks: exercises the
        // cursor/compaction path and would have been O(n²) under the old
        // per-byte ArrayList deframer.
        val payload = ByteArray(64 * 1024) { (it * 31 + 7).toByte() }
        val framed = FrameCodec.encode(payload)
        val deframer = FrameCodec.Deframer()

        val collected = ArrayList<ByteArray>()
        var offset = 0
        val chunk = 1500 // MTU-ish TCP segments
        while (offset < framed.size) {
            val end = minOf(offset + chunk, framed.size)
            collected += deframer.push(framed.copyOfRange(offset, end))
            offset = end
        }

        assertEquals(1, collected.size)
        assertArrayEquals(payload, collected[0])
    }

    @Test
    fun deframer_emitsMultipleFramesFromOnePush() {
        val a = ByteArray(3000) { it.toByte() }
        val b = ByteArray(5000) { (it * 2).toByte() }
        val stream = FrameCodec.encode(a) + FrameCodec.encode(b)
        val out = FrameCodec.Deframer().push(stream)
        assertEquals(2, out.size)
        assertArrayEquals(a, out[0])
        assertArrayEquals(b, out[1])
    }

    @Test
    fun deframer_holdsPartialFrameUntilComplete() {
        val payload = ByteArray(10_000) { it.toByte() }
        val framed = FrameCodec.encode(payload)
        val deframer = FrameCodec.Deframer()
        // First push stops mid-payload: nothing should be emitted yet.
        assertEquals(0, deframer.push(framed.copyOfRange(0, 4096)).size)
        val out = deframer.push(framed.copyOfRange(4096, framed.size))
        assertEquals(1, out.size)
        assertArrayEquals(payload, out[0])
    }

    @Test
    fun protocolVersion_matchesIos() {
        assertEquals(3, WireProtocol.version)
        assertEquals("hostSleeping", WireMessage.hostSleeping)
        assertEquals("bye", WireMessage.bye)
    }
}
