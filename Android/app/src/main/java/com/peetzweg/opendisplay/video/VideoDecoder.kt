package com.peetzweg.opendisplay.video

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Annex-B H.264 → [MediaCodec] → [surface], paced like the iOS receiver.
 *
 * iOS default path (`AVSampleBufferDisplayLayer`):
 * - Enqueue is non-blocking on the network thread
 * - Every access unit is fed in order (no mid-GOP drops)
 * - `DisplayImmediately` — present as soon as decoded
 * - Background: `renderingPaused` drops at the door; resume asks for IDR
 *
 * We mirror that with a dedicated decode thread + shallow in-order queue, wait
 * briefly for input buffers instead of punching GOP holes, and only apply
 * latest-wins on **output** buffers (skip display backlog).
 *
 * ChromeOS ARC: prefer `c2.vda.avc.decoder` on SurfaceView for full-panel HW.
 */
class VideoDecoder(
    private val surface: Surface,
    private val preferSoftware: Boolean = false,
    /** Prefer ARC `c2.vda.avc.decoder` (Chromebook hardware). */
    private val preferHardwareAvc: Boolean = false,
) {
    private var codec: MediaCodec? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private val bufferInfo = MediaCodec.BufferInfo()

    /** In-order AUs waiting for decode — depth keeps VDA fed without punching GOPs. */
    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CAP)
    private val drainScheduled = AtomicBoolean(false)
    private var droppedSinceKf: Int = 0
    private val decodeExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "VideoDecoder").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
        }
    }
    /** Queued→rendered latency for the perf overlay (iOS `decodeP50`). */
    val timings = DecodeTimings()

    // setOnFrameRenderedListener needs a Looper. The decode thread has none,
    // and the main thread would add UI-queue delay to a latency measurement —
    // so give the callback its own thread. (We record the listener's own
    // nanoTime, so its delivery delay does not bias the sample.)
    private val callbackThread = HandlerThread("VideoDecoder-cb").apply {
        isDaemon = true
        start()
    }
    private val callbackHandler = Handler(callbackThread.looper)

    private val released = AtomicBoolean(false)

    /**
     * iOS `renderingPaused` — drop frames while backgrounded.
     * Chromebook ARC often delivers spurious onStop while the panel stays
     * visible; callers should leave this false on ARC (see MainActivity).
     */
    @Volatile
    var renderingPaused: Boolean = false
        set(value) {
            field = value
            if (value) queue.clear()
        }

    @Volatile
    var hasRendered: Boolean = false
        private set

    @Volatile
    private var keyframeRequested: Boolean = false

    /**
     * After codec create/rebuild, reject non-IDR access units until one sync
     * frame is queued. Feeding P-frames into a fresh ARC VDA is the usual
     * Chromebook "solid green" failure after reconnect.
     */
    @Volatile
    private var awaitingSync: Boolean = true

    /** Back off after a failed VDA allocate so we don't spam create/configure. */
    @Volatile
    private var nextStartCodecAtMs: Long = 0L

    fun consumeNeedsKeyframe(): Boolean {
        if (!keyframeRequested) return false
        keyframeRequested = false
        droppedSinceKf = 0
        return true
    }

    /**
     * Non-blocking — safe to call from the TCP reader (iOS enqueue analogue).
     * Preserves decode order. On overflow ask for an IDR after several misses
     * (don't clear the live queue — that blacks Chromebook VDA on connect).
     */
    fun feedAnnexB(frame: ByteArray) {
        if (released.get() || renderingPaused) return
        val copy = frame.copyOf()
        if (!queue.offer(copy)) {
            noteDrop()
            return
        }
        if (drainScheduled.compareAndSet(false, true)) {
            try {
                decodeExecutor.execute { drainQueue() }
            } catch (_: RejectedExecutionException) {
                drainScheduled.set(false)
            }
        }
    }

    private fun noteDrop() {
        droppedSinceKf++
        if (droppedSinceKf >= DROP_KF_THRESHOLD) {
            keyframeRequested = true
        }
    }

    fun release() {
        if (released.getAndSet(true)) return
        queue.clear()
        // Release on the decode thread. [codecLifecycleLock] serializes stop/release
        // with the next instance's configure — overlapping ARC VDA allocate after
        // screen-wake reconnect is what wedges the panel black permanently.
        try {
            decodeExecutor.execute {
                synchronized(this@VideoDecoder) {
                    releaseCodec()
                    sps = null
                    pps = null
                    hasRendered = false
                    awaitingSync = true
                    keyframeRequested = false
                    nextStartCodecAtMs = 0L
                }
                callbackThread.quitSafely()
            }
        } catch (_: RejectedExecutionException) {
            synchronized(this) { releaseCodec() }
        }
        decodeExecutor.shutdown()
    }

    private fun drainQueue() {
        try {
            while (!released.get()) {
                val frame = queue.poll() ?: break
                synchronized(this) {
                    if (!released.get() && !renderingPaused) decodeOne(frame)
                }
            }
        } finally {
            drainScheduled.set(false)
            if (!released.get() && !renderingPaused &&
                queue.isNotEmpty() && drainScheduled.compareAndSet(false, true)
            ) {
                try {
                    decodeExecutor.execute { drainQueue() }
                } catch (_: RejectedExecutionException) {
                    drainScheduled.set(false)
                }
            }
        }
    }

    @Synchronized
    private fun decodeOne(frame: ByteArray) {
        val parametersChanged = scanForParameterSets(frame)
        if (codec != null && parametersChanged) releaseCodec()
        val isSync = containsIdr(frame)
        if (codec == null) {
            val s = sps
            val p = pps
            if (s == null || p == null) return
            // Don't burn a VDA allocate on a P-frame — ARC often finishes
            // configure just in time to miss the IDR, then stays black until
            // the next (rare) keyframe.
            if (!isSync) {
                keyframeRequested = true
                return
            }
            if (System.currentTimeMillis() < nextStartCodecAtMs) {
                keyframeRequested = true
                return
            }
            startCodec(s, p)
        }
        if (awaitingSync && !isSync) {
            // Fresh codec / post-rebuild — wait for IDR; P-frames green VDA.
            keyframeRequested = true
            return
        }
        val accessUnit = annexBWithoutParameterSets(frame) ?: return
        val c = codec ?: run {
            // startCodec failed or still backing off — ask for another IDR.
            keyframeRequested = true
            return
        }
        try {
            drainOutput(c)
            // Wait briefly for an input slot (iOS never skips mid-GOP).
            var index = c.dequeueInputBuffer(0)
            if (index < 0) {
                index = c.dequeueInputBuffer(if (isSync) SYNC_TIMEOUT_US else INPUT_TIMEOUT_US)
            }
            if (index < 0) {
                // Soft/HW backed up — don't punch a hole; ask IDR after several.
                if (!isSync) noteDrop()
                drainOutput(c)
                return
            }
            val input = c.getInputBuffer(index) ?: return
            if (accessUnit.size > input.capacity()) {
                Log.w(TAG, "input too large: ${accessUnit.size} > ${input.capacity()}")
                return
            }
            input.clear()
            input.put(accessUnit)
            val flags = if (isSync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            val ptsUs = System.nanoTime() / 1000
            c.queueInputBuffer(index, 0, accessUnit.size, ptsUs, flags)
            if (isSync) awaitingSync = false
            timings.noteQueued(ptsUs, System.nanoTime())
            // Nothing queued behind this frame: wait briefly so it reaches the
            // panel now rather than on the next frame off the network. Without
            // this the last frame of every burst sits in the codec until motion
            // resumes — iOS's DisplayImmediately has no such hole.
            drainOutput(c, if (queue.isEmpty()) TAIL_TIMEOUT_US else 0)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "decodeOne: ${e.message}")
            // VDA often dies after a SurfaceView abandon — rebuild on next IDR.
            releaseCodec()
            keyframeRequested = true
            awaitingSync = true
            sps = null
            pps = null
        }
    }

    private fun releaseCodec() {
        val c = codec
        codec = null
        hasRendered = false
        awaitingSync = true
        if (c == null) return
        synchronized(codecLifecycleLock) {
            try {
                c.stop()
            } catch (_: IllegalStateException) {
            }
            try {
                c.release()
            } catch (_: IllegalStateException) {
            }
        }
    }

    /**
     * Present only the newest decoded buffer (DisplayImmediately analogue).
     *
     * [firstTimeoutUs] applies to the first dequeue only: callers use it to
     * wait for a frame that is still decoding. Subsequent dequeues stay at 0
     * so the loop drains what is ready and returns.
     */
    private fun drainOutput(c: MediaCodec, firstTimeoutUs: Long = 0) {
        var latest = -1
        var timeoutUs = firstTimeoutUs
        while (true) {
            val index = c.dequeueOutputBuffer(bufferInfo, timeoutUs)
            timeoutUs = 0
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.i(TAG, "output format: ${c.outputFormat}")
                continue
            }
            if (index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) continue
            if (index < 0) break
            if (latest >= 0) {
                try {
                    c.releaseOutputBuffer(latest, false)
                } catch (_: IllegalStateException) {
                }
            }
            latest = index
        }
        if (latest >= 0) {
            c.releaseOutputBuffer(latest, true)
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
        val now = System.currentTimeMillis()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, DEFAULT_WIDTH, DEFAULT_HEIGHT)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(START_CODE + sps))
        format.setByteBuffer("csd-1", ByteBuffer.wrap(START_CODE + pps))
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
        if (Build.VERSION.SDK_INT >= 30) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        if (Build.VERSION.SDK_INT >= 23) {
            format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        // Desktop capture is full-range; without this ARC VDA often assumes
        // limited-range and the Chromebook panel looks a notch too dark.
        if (Build.VERSION.SDK_INT >= 24) {
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
            format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
        try {
            // Hold the process-wide lock across create→configure→start so a
            // prior instance's release finishes first (post-sleep thrash).
            synchronized(codecLifecycleLock) {
                val c = createCodec()
                Log.i(TAG, "configure ${c.name} software=$preferSoftware")
                c.configure(format, surface, null, 0)
                c.start()
                // Reset on the NEW codec, not on teardown: a mid-session rebuild
                // (VDA after a surface abandon, a parameter-set change) must not
                // blank the metric we are judging this work by, and any samples
                // still pending belong to the codec that just went away.
                timings.clear()
                c.setOnFrameRenderedListener({ _, presentationTimeUs, nanoTime ->
                    timings.noteRendered(presentationTimeUs, nanoTime)
                }, callbackHandler)
                codec = c
            }
            nextStartCodecAtMs = 0L
        } catch (e: Exception) {
            Log.e(TAG, "startCodec failed", e)
            codec = null
            // ARC VDA allocate often needs several seconds after sleep; hammering
            // create/configure while the previous attempt is dying wedges it.
            nextStartCodecAtMs = now + START_CODEC_BACKOFF_MS
            keyframeRequested = true
        }
    }

    private fun createCodec(): MediaCodec {
        if (preferSoftware) {
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
        } else if (preferHardwareAvc) {
            for (name in HARDWARE_AVC_NAMES) {
                try {
                    return MediaCodec.createByCodecName(name)
                } catch (e: Exception) {
                    Log.w(TAG, "hardware codec $name failed: ${e.message}")
                }
            }
            Log.w(TAG, "no named VDA AVC; falling back to default")
        }
        return MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    }

    companion object {
        private const val TAG = "VideoDecoder"
        /**
         * Serializes ARC VDA create/configure/release across decoder instances.
         * Overlapping allocate after screen-wake reconnect wedges the panel black.
         */
        private val codecLifecycleLock = Any()
        /** Keep enough AUs for VDA warmup; shallow (2) blacks Chromebook on connect. */
        private const val QUEUE_CAP = 8
        private const val DROP_KF_THRESHOLD = 6
        private const val INPUT_TIMEOUT_US = 8_000L
        private const val SYNC_TIMEOUT_US = 100_000L
        /** Half a 60fps frame — long enough for the tail frame, short enough
         *  that a stalled decoder does not hold the decode thread. */
        private const val TAIL_TIMEOUT_US = 8_000L
        /** After a failed configure/allocate, wait before retrying (Chromebook VDA). */
        private const val START_CODEC_BACKOFF_MS = 2_000L
        private const val NAL_SPS = 7
        private const val NAL_PPS = 8
        private const val NAL_IDR = 5
        private const val DEFAULT_WIDTH = 1280
        private const val DEFAULT_HEIGHT = 720
        private val START_CODE = byteArrayOf(0, 0, 0, 1)
        // OMX.google first: reliable soft path; c2.android often fails init.
        private val SOFTWARE_AVC_NAMES = listOf(
            "OMX.google.h264.decoder",
            "c2.android.avc.decoder",
        )
        private val HARDWARE_AVC_NAMES = listOf(
            "c2.vda.avc.decoder",
        )

        fun containsIdr(frame: ByteArray): Boolean =
            splitAnnexB(frame).any { it.isNotEmpty() && (it[0].toInt() and 0x1F) == NAL_IDR }

        fun preferSoftwareDecoder(): Boolean {
            // Chromebook now uses VDA hardware on SurfaceView; soft is fallback only.
            return false
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
                if (name.contains("google", ignoreCase = true)) return name
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
