package com.peetzweg.opendisplay.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpVideoFecTest {
    @Test
    fun reedSolomon_recoversSixteenPlusFourCounterexample() {
        val data = Array(16) { shard ->
            ByteArray(64) { byte -> (shard * 31 + byte * 17).toByte() }
        }
        val parity = ReedSolomon.encode(data, parityCount = 4)
        val received = arrayOfNulls<ByteArray>(20)
        for (index in data.indices) received[index] = data[index]
        for (index in parity.indices) received[data.size + index] = parity[index]

        received[0] = null
        received[1] = null
        received[2] = null
        received[18] = null

        val decoded = ReedSolomon.decode(received, dataCount = data.size)
        assertNotNull(decoded)
        for (index in data.indices) assertArrayEquals(data[index], decoded!![index])
    }

    @Test
    fun packageFrame_emitsDataAndParityDatagrams() {
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
        val dataPackets = packaged.packets.count { !it.isParity }
        val parityPackets = packaged.packets.count { it.isParity }
        assertTrue(parityPackets > 0)
        assertEquals(dataPackets + parityPackets, packaged.packets.size)
    }

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
    fun assemble_recoversMultipleDataErasuresWithMixedParityLossAndReordering() {
        val au = ByteArray(8000) { i -> (i * 29 % 251).toByte() }
        val packaged = UdpVideoPackager.packageFrame(
            au = au,
            frameId = 10L,
            startSeq = 20,
            keyframe = false,
            fecPct = 50,
            captureMs = 3L,
            sendMs = 4L,
        )
        val retained = packaged.packets.filter { packet ->
            val header = UdpVideoProtocol.decodeHeader(packet.datagram)!!
            header.shardIndex !in setOf(1, 4, header.dataShardCount)
        }.reversed()

        val assembler = UdpFrameAssembler()
        var out: UdpFrameAssembler.AssembledFrame? = null
        for (packet in retained) {
            assembler.offer(packet.datagram)?.let { out = it }
        }

        assertNotNull(out)
        assertArrayEquals(au, out!!.annexB)
        assertEquals(true, out.recoveredByFec)
    }

    @Test
    fun assemble_rejectsDelayedPacketsAfterFrameDelivery() {
        val first = UdpVideoPackager.packageFrame(
            au = ByteArray(1600) { it.toByte() },
            frameId = 20L,
            startSeq = 0,
            keyframe = false,
            fecPct = 20,
            captureMs = 5L,
            sendMs = 6L,
        )
        val secondAu = ByteArray(1700) { (it * 7).toByte() }
        val second = UdpVideoPackager.packageFrame(
            au = secondAu,
            frameId = 21L,
            startSeq = first.nextSeq,
            keyframe = true,
            fecPct = 20,
            captureMs = 7L,
            sendMs = 8L,
        )
        val assembler = UdpFrameAssembler()
        var firstOut: UdpFrameAssembler.AssembledFrame? = null
        for (packet in first.packets) {
            assembler.offer(packet.datagram)?.let { firstOut = it }
        }
        assertNotNull(firstOut)

        for (packet in first.packets.reversed()) {
            assertNull(assembler.offer(packet.datagram))
        }

        var secondOut: UdpFrameAssembler.AssembledFrame? = null
        for (packet in second.packets.reversed()) {
            assembler.offer(packet.datagram)?.let { secondOut = it }
        }
        assertNotNull(secondOut)
        assertArrayEquals(secondAu, secondOut!!.annexB)
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

    @Test
    fun packageFrame_oversizedIdrSkipsFecWithoutThrowing() {
        // >255 data shards: RS block cannot include FEC. Must still packetize
        // (Mac used to trap in ReedSolomon.encode on the first UDP IDR).
        val au = ByteArray(UdpVideoProtocol.MAX_PAYLOAD * 260)
        val packaged = UdpVideoPackager.packageFrame(
            au = au,
            frameId = 1L,
            startSeq = 0,
            keyframe = true,
            fecPct = 20,
            captureMs = 1L,
            sendMs = 2L,
        )
        assertTrue(packaged.packets.size > UdpVideoProtocol.MAX_RS_SHARDS)
        assertEquals(0, packaged.packets.count { it.isParity })
        val header = UdpVideoProtocol.decodeHeader(packaged.packets.first().datagram)!!
        assertEquals(0, header.fecPct)
        assertTrue(header.dataShardCount > UdpVideoProtocol.MAX_RS_SHARDS)

        val assembler = UdpFrameAssembler()
        var out: UdpFrameAssembler.AssembledFrame? = null
        for (packet in packaged.packets) {
            assembler.offer(packet.datagram)?.let { out = it }
        }
        assertNotNull(out)
        assertArrayEquals(au, out!!.annexB)
    }
}
