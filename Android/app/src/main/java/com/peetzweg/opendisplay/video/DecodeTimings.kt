package com.peetzweg.opendisplay.video

/**
 * Ring buffer of queued→rendered latencies, keyed by the presentation
 * timestamp stamped on each access unit.
 *
 * iOS measures this stage two ways — `VTDecompressionSessionDecodeFrame`
 * timing (`PhoneReceiver.decodeWindow`) and `CAMetalDrawable`'s presented
 * handler. `MediaCodec.setOnFrameRenderedListener` is the Android analogue:
 * it reports the pts we queued plus a `System.nanoTime()` render stamp, so we
 * only need to remember when each pts went in.
 *
 * Timestamps are nanos on the `System.nanoTime()` clock; samples are ms.
 * Called from the decode thread and the codec callback thread — synchronized.
 */
class DecodeTimings(private val capacity: Int = 120) {
    private val queuedAt = LinkedHashMap<Long, Long>()
    private val samples = ArrayList<Double>(capacity)

    @Synchronized
    fun noteQueued(ptsUs: Long, atNanos: Long) {
        queuedAt[ptsUs] = atNanos
        // Frames the decoder drops never come back — bound the pending map.
        while (queuedAt.size > capacity) {
            queuedAt.remove(queuedAt.keys.first())
        }
    }

    @Synchronized
    fun noteRendered(ptsUs: Long, atNanos: Long) {
        val queued = queuedAt.remove(ptsUs) ?: return
        val ms = (atNanos - queued) / 1_000_000.0
        if (ms < 0.0 || ms > 5_000.0) return
        samples.add(ms)
        if (samples.size > capacity) samples.removeAt(0)
    }

    /** Snapshot for the 1Hz perf window; the ring survives for the next one. */
    @Synchronized
    fun snapshot(): List<Double> = ArrayList(samples)

    /**
     * Snapshot the current samples and clear them so a subsequent idle
     * window reports empty rather than replaying the last frames rendered.
     * [queuedAt] is left untouched — those pts are still legitimately
     * in-flight and must survive to be matched by a later [noteRendered].
     */
    @Synchronized
    fun snapshotAndDrain(): List<Double> {
        val result = ArrayList(samples)
        samples.clear()
        return result
    }

    @Synchronized
    fun clear() {
        queuedAt.clear()
        samples.clear()
    }
}
