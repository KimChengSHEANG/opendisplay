package com.peetzweg.opendisplay.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UdpVideoProtocolTest {
    @Test
    fun header_roundTrips() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val dgram = UdpVideoProtocol.buildDatagram(
            flags = UdpVideoProtocol.FLAG_KEYFRAME or UdpVideoProtocol.FLAG_START or UdpVideoProtocol.FLAG_END,
            seq = 9001,
            frameId = 42L,
            shardIndex = 0,
            dataShardCount = 1,
            fecPct = 20,
            captureMs = 1_000L,
            sendMs = 1_012L,
            payload = payload,
        )
        val header = UdpVideoProtocol.decodeHeader(dgram)!!
        assertEquals(UdpVideoProtocol.VERSION, header.version)
        assertEquals(9001, header.seq)
        assertEquals(42L, header.frameId)
        assertEquals(0, header.shardIndex)
        assertEquals(1, header.dataShardCount)
        assertEquals(20, header.fecPct)
        assertEquals(1_000L, header.captureMs)
        assertEquals(1_012L, header.sendMs)
        assertEquals(true, header.isKeyframe)
        assertArrayEquals(
            payload,
            dgram.copyOfRange(UdpVideoProtocol.HEADER_SIZE, dgram.size),
        )
    }

    @Test
    fun decodeHeader_rejectsWrongVersion() {
        val bad = ByteArray(UdpVideoProtocol.HEADER_SIZE)
        bad[0] = 99
        assertNull(UdpVideoProtocol.decodeHeader(bad))
    }

    @Test
    fun parityShardCount_twentyPercent() {
        assertEquals(2, UdpVideoProtocol.parityShardCount(dataShardCount = 10, fecPct = 20))
        assertEquals(0, UdpVideoProtocol.parityShardCount(dataShardCount = 10, fecPct = 0))
    }
}
