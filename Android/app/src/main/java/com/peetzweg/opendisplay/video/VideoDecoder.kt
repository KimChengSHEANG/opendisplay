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
 * `MacSender.swift`'s `annexB(from:)`. Parameter sets go into `csd-0`/`csd-1`
 * only; VCL NALs are queued as input (same split iOS uses before
 * `CMSampleBuffer`).
 */
class VideoDecoder(private val surface: Surface) {
    private var codec: MediaCodec? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private val bufferInfo = MediaCodec.BufferInfo()

    /** Feed one Annex B access unit (one or more start-code-delimited NAL units). */
    @Synchronized
    fun feedAnnexB(frame: ByteArray) {
        val parametersChanged = scanForParameterSets(frame)
        // Mid-stream SPS/PPS change (e.g. the Mac's virtual display resized or
        // rotated) can't be applied to a running codec — tear it down and
        // reconfigure with the new parameter sets. Mirrors iOS resetting its
        // `formatDesc` when SPS/PPS change. See `PhoneReceiver.swift`.
        if (codec != null && parametersChanged) releaseCodec()
        if (codec == null) {
            val s = sps
            val p = pps
            if (s != null && p != null) startCodec(s, p) else return
        }
        val accessUnit = annexBWithoutParameterSets(frame) ?: return
        val c = codec ?: return
        try {
            val index = c.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                val input = c.getInputBuffer(index) ?: return
                if (accessUnit.size > input.capacity()) return
                input.clear()
                input.put(accessUnit)
                // PTS 0 + render=true → present immediately (low-latency path).
                c.queueInputBuffer(index, 0, accessUnit.size, 0L, 0)
            }
            drainOutput(c)
        } catch (_: IllegalStateException) {
            // Codec was concurrently released, or hit an internal error state — drop this frame.
        }
    }

    /** Releases the underlying codec and clears cached parameter sets. */
    @Synchronized
    fun release() {
        releaseCodec()
        sps = null
        pps = null
    }

    /** Stop and release the codec, but keep the (possibly updated) SPS/PPS so
     *  [feedAnnexB] can immediately reconfigure. Safe to call more than once. */
    private fun releaseCodec() {
        val c = codec
        codec = null
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
            // Always render; INFO_OUTPUT_FORMAT_CHANGED returns -2 and is skipped above.
            c.releaseOutputBuffer(index, true)
        }
    }

    /** Updates cached SPS/PPS from [frame]; returns true if either changed. */
    private fun scanForParameterSets(frame: ByteArray): Boolean {
        var changed = false
        for (nalu in splitAnnexB(frame)) {
            if (nalu.isEmpty()) continue
            when (nalu[0].toInt() and 0x1F) {
                NAL_SPS -> if (!nalu.contentEquals(sps)) { sps = nalu; changed = true }
                NAL_PPS -> if (!nalu.contentEquals(pps)) { pps = nalu; changed = true }
            }
        }
        return changed
    }

    private fun startCodec(sps: ByteArray, pps: ByteArray) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, DEFAULT_WIDTH, DEFAULT_HEIGHT)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(START_CODE + sps))
        format.setByteBuffer("csd-1", ByteBuffer.wrap(START_CODE + pps))
        try {
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(format, surface, null, 0)
            c.start()
            codec = c
        } catch (_: Exception) {
            // Surface gone / unsupported stream / ARC decoder glitch — drop
            // until the next SPS/PPS keyframe retries startCodec.
            codec = null
        }
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

        /**
         * Rebuild Annex B with SPS/PPS removed. Parameter sets are supplied via
         * MediaFormat CSD; feeding them again as slice data makes some Android
         * / ARC decoders paint a solid green surface.
         */
        fun annexBWithoutParameterSets(frame: ByteArray): ByteArray? {
            val nalus = splitAnnexB(frame)
            var size = 0
            val kept = ArrayList<ByteArray>(nalus.size)
            for (nalu in nalus) {
                if (nalu.isEmpty()) continue
                when (nalu[0].toInt() and 0x1F) {
                    NAL_SPS, NAL_PPS -> continue
                    else -> {
                        kept.add(nalu)
                        size += START_CODE.size + nalu.size
                    }
                }
            }
            if (kept.isEmpty()) return null
            val out = ByteArray(size)
            var o = 0
            for (nalu in kept) {
                System.arraycopy(START_CODE, 0, out, o, START_CODE.size)
                o += START_CODE.size
                System.arraycopy(nalu, 0, out, o, nalu.size)
                o += nalu.size
            }
            return out
        }

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
