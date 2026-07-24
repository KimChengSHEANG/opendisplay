package com.peetzweg.opendisplay.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoDecoderTest {
    @Test
    fun splitAnnexB_fourByteStartCodes_splitsSpsPpsIdr() {
        val sps = byteArrayOf(0x67, 0x42, 0x00)
        val pps = byteArrayOf(0x68, 0xCE.toByte())
        val idr = byteArrayOf(0x65, 0x01, 0x02, 0x03)
        val data = byteArrayOf(0, 0, 0, 1) + sps +
            byteArrayOf(0, 0, 0, 1) + pps +
            byteArrayOf(0, 0, 0, 1) + idr

        val nalus = VideoDecoder.splitAnnexB(data)

        assertEquals(3, nalus.size)
        assertArrayEquals(sps, nalus[0])
        assertArrayEquals(pps, nalus[1])
        assertArrayEquals(idr, nalus[2])
    }

    @Test
    fun splitAnnexB_mixedThreeAndFourByteStartCodes() {
        val pps = byteArrayOf(0x68, 0xCE.toByte())
        val idr = byteArrayOf(0x65, 0x01)
        val data = byteArrayOf(0, 0, 1) + pps + byteArrayOf(0, 0, 0, 1) + idr

        val nalus = VideoDecoder.splitAnnexB(data)

        assertEquals(2, nalus.size)
        assertArrayEquals(pps, nalus[0])
        assertArrayEquals(idr, nalus[1])
    }

    @Test
    fun splitAnnexB_noStartCode_returnsEmpty() {
        val nalus = VideoDecoder.splitAnnexB(byteArrayOf(1, 2, 3))
        assertEquals(0, nalus.size)
    }

    @Test
    fun annexBWithoutParameterSets_stripsSpsPpsKeepsIdr() {
        val sps = byteArrayOf(0x67, 0x42, 0x00)
        val pps = byteArrayOf(0x68, 0xCE.toByte())
        val idr = byteArrayOf(0x65, 0x01, 0x02, 0x03)
        val data = byteArrayOf(0, 0, 0, 1) + sps +
            byteArrayOf(0, 0, 0, 1) + pps +
            byteArrayOf(0, 0, 0, 1) + idr

        val stripped = VideoDecoder.annexBWithoutParameterSets(data)!!
        val nalus = VideoDecoder.splitAnnexB(stripped)

        assertEquals(1, nalus.size)
        assertArrayEquals(idr, nalus[0])
    }

    @Test
    fun annexBWithoutParameterSets_onlyParameterSets_returnsNull() {
        val sps = byteArrayOf(0x67, 0x42)
        val pps = byteArrayOf(0x68, 0xCE.toByte())
        val data = byteArrayOf(0, 0, 0, 1) + sps + byteArrayOf(0, 0, 0, 1) + pps
        assertEquals(null, VideoDecoder.annexBWithoutParameterSets(data))
    }
}
