package com.peetzweg.opendisplay.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UdpVideoFecTest {
    @Test
    fun packageAndAssemble_roundTripWithoutLoss() {
        val au = ByteArray(2500) { i -> (i % 251).toByte() }
        val packaged = UdpVideoPackager.packageFrame(
            au = au,
            frameId = 7L,
            startSeq = 100,
            keyframe = true,
            fecPct = 20,
            captureMs = 50L,
            sendMs = 60L,
        )
        val assembler = UdpFrameAssembler()
        var out: UdpFrameAssembler.AssembledFrame? = null
        for (pkt in packaged.packets) {
            val got = assembler.offer(pkt.datagram)
            if (got != null) out = got
        }
        assertNotNull(out)
        assertArrayEquals(au, out!!.annexB)
        assertEquals(true, out.isKeyframe)
        assertEquals(false, out.recoveredByFec)
    }

    @Test
    fun assemble_recoversOneMissingDataShardViaFec() {
        val au = ByteArray(1800) { i -> (i % 199).toByte() }
        val packaged = UdpVideoPackager.packageFrame(
            au = au,
            frameId = 9L,
            startSeq = 1,
            keyframe = false,
            fecPct = 20,
            captureMs = 1L,
            sendMs = 2L,
        )
        val dataPackets = packaged.packets.filter { !it.isParity }
        val parityPackets = packaged.packets.filter { it.isParity }
        require(dataPackets.size >= 2 && parityPackets.isNotEmpty())

        val assembler = UdpFrameAssembler()
        var out: UdpFrameAssembler.AssembledFrame? = null
        // Drop data shard index 1; keep others + all parity.
        for ((idx, pkt) in dataPackets.withIndex()) {
            if (idx == 1) continue
            assembler.offer(pkt.datagram)?.let { out = it }
        }
        for (pkt in parityPackets) {
            assembler.offer(pkt.datagram)?.let { out = it }
        }
        assertNotNull(out)
        assertArrayEquals(au, out!!.annexB)
        assertEquals(true, out.recoveredByFec)
    }

    @Test
    fun assemble_returnsNullWhenTooManyShardsMissing() {
        val au = ByteArray(3000) { 7 }
        val packaged = UdpVideoPackager.packageFrame(
            au = au,
            frameId = 3L,
            startSeq = 0,
            keyframe = false,
            fecPct = 20,
            captureMs = 1L,
            sendMs = 2L,
        )
        val assembler = UdpFrameAssembler()
        // Offer only the first data shard — far below recoverability.
        assertNull(assembler.offer(packaged.packets.first().datagram))
    }
}
