package com.peetzweg.opendisplay.video

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Decodes an Annex B `video/avc` (H.264) elementary stream onto [surface] via
 * `MediaCodec`. On ChromeOS ARC the hardware `c2.vda.avc.decoder` path often
 * paints a solid green TextureView even after a successful format change —
 * we prefer a software AVC decoder there. Elsewhere we use the default
 * hardware decoder.
 *
 * Codec starts on the first in-band SPS+PPS (Mac prepends both on keyframes).
 * Parameter sets go into `csd-0`/`csd-1` only; VCL NALs are queued as input.
 */
class VideoDecoder(
    private val surface: Surface,
    private val preferSoftware: Boolean = false,
) {
    private var codec: MediaCodec? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private val bufferInfo = MediaCodec.BufferInfo()

    /** True after at least one decoded buffer has been released to the surface. */
    @Volatile
    var hasRendered: Boolean = false
        private set

    /** Feed one Annex B access unit (one or more start-code-delimited NAL units). */
    @Synchronized
    fun feedAnnexB(frame: ByteArray) {
        val parametersChanged = scanForParameterSets(frame)
        if (codec != null && parametersChanged) releaseCodec()
        if (codec == null) {
            val s = sps
            val p = pps
            if (s != null && p != null) startCodec(s, p) else return
        }
        val accessUnit = annexBWithoutParameterSets(frame) ?: return
        val isSync = containsIdr(frame)
        val c = codec ?: return
        try {
            var index = c.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0 && isSync) {
                index = c.dequeueInputBuffer(SYNC_TIMEOUT_US)
            }
            if (index >= 0) {
                val input = c.getInputBuffer(index) ?: return
                if (accessUnit.size > input.capacity()) {
                    Log.w(TAG, "input too large: ${accessUnit.size} > ${input.capacity()}")
                    return
                }
                input.clear()
                input.put(accessUnit)
                val flags = if (isSync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                c.queueInputBuffer(index, 0, accessUnit.size, System.nanoTime() / 1000, flags)
            }
            drainOutput(c)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "feedAnnexB: ${e.message}")
        }
    }

    @Synchronized
    fun release() {
        releaseCodec()
        sps = null
        pps = null
        hasRendered = false
    }

    private fun releaseCodec() {
        val c = codec
        codec = null
        hasRendered = false
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
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.i(TAG, "output format: ${c.outputFormat}")
                continue
            }
            if (index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) continue
            if (index < 0) return
            c.releaseOutputBuffer(index, true)
            hasRendered = true
        }
    }

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
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
        try {
            val c = createCodec()
            Log.i(TAG, "configure ${c.name} software=$preferSoftware")
            c.configure(format, surface, null, 0)
            c.start()
            codec = c
        } catch (e: Exception) {
            Log.e(TAG, "startCodec failed", e)
            codec = null
        }
    }

    private fun createCodec(): MediaCodec {
        if (preferSoftware) {
            // Prefer known software names first — MediaCodecList ordering on
            // ARC often returns c2.vda.avc.decoder as the "default" AVC path,
            // which greens TextureView/SurfaceView composites on Cheets.
            for (name in SOFTWARE_AVC_NAMES) {
                try {
                    return MediaCodec.createByCodecName(name)
                } catch (e: Exception) {
                    Log.w(TAG, "software codec $name failed: ${e.message}")
                }
            }
            findSoftwareAvcDecoder()?.let { name ->
                try {
                    return MediaCodec.createByCodecName(name)
                } catch (e: Exception) {
                    Log.w(TAG, "software codec $name failed: ${e.message}")
                }
            }
            Log.w(TAG, "no software AVC decoder; falling back to default")
        }
        return MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    }

    companion object {
        private const val TAG = "VideoDecoder"
        private const val TIMEOUT_US = 10_000L
        private const val SYNC_TIMEOUT_US = 100_000L
        private const val NAL_SPS = 7
        private const val NAL_PPS = 8
        private const val NAL_IDR = 5
        private const val DEFAULT_WIDTH = 1280
        private const val DEFAULT_HEIGHT = 720
        private val START_CODE = byteArrayOf(0, 0, 0, 1)
        private val SOFTWARE_AVC_NAMES = listOf(
            "c2.android.avc.decoder",
            "OMX.google.h264.decoder",
        )

        fun containsIdr(frame: ByteArray): Boolean =
            splitAnnexB(frame).any { it.isNotEmpty() && (it[0].toInt() and 0x1F) == NAL_IDR }

        /**
         * ChromeOS ARC's VDA hardware path frequently composites green onto
         * TextureView; software AVC is the reliable fallback there.
         */
        fun preferSoftwareDecoder(): Boolean {
            val tags = listOf(
                Build.DEVICE?.lowercase().orEmpty(),
                Build.MODEL?.lowercase().orEmpty(),
                Build.PRODUCT?.lowercase().orEmpty(),
                Build.BRAND?.lowercase().orEmpty(),
                Build.MANUFACTURER?.lowercase().orEmpty(),
            )
            if (tags.any { it.contains("chromebook") || it.contains("chromeos") || it.contains("cheets") }) {
                return true
            }
            // ARC feature flags (set on ChromeOS Android containers).
            return try {
                val clazz = Class.forName("android.os.SystemProperties")
                val get = clazz.getMethod("get", String::class.java, String::class.java)
                val arc = get.invoke(null, "ro.boot.container", "") as String
                arc.isNotEmpty()
            } catch (_: Exception) {
                false
            }
        }

        private fun findSoftwareAvcDecoder(): String? {
            val list = MediaCodecList(MediaCodecList.ALL_CODECS)
            var soft: String? = null
            for (info in list.codecInfos) {
                if (info.isEncoder) continue
                if (!info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }) continue
                val name = info.name
                val isSoftware = if (Build.VERSION.SDK_INT >= 29) {
                    info.isSoftwareOnly
                } else {
                    name.contains("android", ignoreCase = true)
                        || name.contains("google", ignoreCase = true)
                        || name.startsWith("c2.android.", ignoreCase = true)
                }
                if (!isSoftware) continue
                // Prefer modern c2 software over legacy OMX.google.
                if (name.contains("c2.android", ignoreCase = true)) return name
                if (soft == null) soft = name
            }
            return soft
        }

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

        fun splitAnnexB(data: ByteArray): List<ByteArray> {
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
