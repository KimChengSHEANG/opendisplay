package com.peetzweg.opendisplay.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Decodes an Annex B `video/avc` (H.264) elementary stream onto [surface] via
 * hardware `MediaCodec`. Mirrors `PhoneReceiver.swift`'s low-latency path:
 * every decoded frame is released to the surface immediately, no PTS
 * scheduling.
 *
 * The codec isn't started until the first SPS+PPS pair is observed in-band —
 * the Mac prepends both (start-code delimited) ahead of every keyframe, see
 * `MacSender.swift`'s `annexB(from:)`.
 */
class VideoDecoder(private val surface: Surface) {
    private var codec: MediaCodec? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private val bufferInfo = MediaCodec.BufferInfo()

    /** Feed one Annex B access unit (one or more start-code-delimited NAL units). */
    @Synchronized
    fun feedAnnexB(frame: ByteArray) {
        if (codec == null) {
            scanForParameterSets(frame)
            val s = sps
            val p = pps
            if (s != null && p != null) startCodec(s, p) else return
        }
        val c = codec ?: return
        try {
            val index = c.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                val input = c.getInputBuffer(index)
                input?.clear()
                input?.put(frame)
                c.queueInputBuffer(index, 0, frame.size, System.nanoTime() / 1000, 0)
            }
            drainOutput(c)
        } catch (_: IllegalStateException) {
            // Codec was concurrently released, or hit an internal error state — drop this frame.
        }
    }

    /** Releases the underlying codec. Safe to call more than once. */
    @Synchronized
    fun release() {
        val c = codec
        codec = null
        sps = null
        pps = null
        if (c == null) return
        try {
            c.stop()
        } catch (_: IllegalStateException) {
        }
        try {
            c.release()
        } catch (_: IllegalStateException) {
        }
    }

    private fun drainOutput(c: MediaCodec) {
        while (true) {
            val index = c.dequeueOutputBuffer(bufferInfo, 0)
            if (index < 0) return
            c.releaseOutputBuffer(index, true)
        }
    }

    private fun scanForParameterSets(frame: ByteArray) {
        for (nalu in splitAnnexB(frame)) {
            if (nalu.isEmpty()) continue
            when (nalu[0].toInt() and 0x1F) {
                NAL_SPS -> sps = nalu
                NAL_PPS -> pps = nalu
            }
        }
    }

    private fun startCodec(sps: ByteArray, pps: ByteArray) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, DEFAULT_WIDTH, DEFAULT_HEIGHT)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(START_CODE + sps))
        format.setByteBuffer("csd-1", ByteBuffer.wrap(START_CODE + pps))
        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.configure(format, surface, null, 0)
        c.start()
        codec = c
    }

    companion object {
        private const val TIMEOUT_US = 10_000L
        private const val NAL_SPS = 7
        private const val NAL_PPS = 8

        // Real dimensions are irrelevant here: rendering straight to a Surface,
        // MediaCodec resizes its output to whatever the in-band SPS declares.
        private const val DEFAULT_WIDTH = 1280
        private const val DEFAULT_HEIGHT = 720
        private val START_CODE = byteArrayOf(0, 0, 0, 1)

        /** Splits Annex B data on 3- or 4-byte start codes into raw NAL units (start codes stripped). */
        fun splitAnnexB(data: ByteArray): List<ByteArray> {
            // Each entry: (index where the start code begins, index right after it — i.e. NAL payload start).
            val starts = ArrayList<Pair<Int, Int>>()
            var i = 0
            while (i + 2 < data.size) {
                if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                    starts.add(i to i + 3)
                    i += 3
                    continue
                }
                if (i + 3 < data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                    data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
                ) {
                    starts.add(i to i + 4)
                    i += 4
                    continue
                }
                i++
            }
            val nalus = ArrayList<ByteArray>(starts.size)
            for (idx in starts.indices) {
                val from = starts[idx].second
                val to = if (idx + 1 < starts.size) starts[idx + 1].first else data.size
                if (to > from) nalus.add(data.copyOfRange(from, to))
            }
            return nalus
        }
    }
}
