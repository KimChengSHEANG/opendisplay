package com.peetzweg.opendisplay.net

/**
 * UDP send backpressure policy (mirrored in Mac [UdpVideoSender]).
 *
 * A single 1080p IDR with ~20% FEC routinely exceeds 64 shards. Rejecting
 * that frame when the queue is empty blacks Chromebook VDA forever.
 * Frames larger than the RS block (255) are sent data-only (no FEC).
 */
object UdpSendBudget {
    /** Enough for one full RS block (255) plus headroom for one paced batch. */
    const val DEFAULT_MAX_PENDING = 256

    /**
     * Wire cap per frame (≈4 MiB Annex-B / max payload). RS FEC only applies
     * when data+parity ≤ [UdpVideoProtocol.MAX_RS_SHARDS]; larger frames omit FEC.
     */
    const val MAX_FRAME_DATAGRAMS = 4096

    fun shouldNetDrop(pendingDatagrams: Int, maxPending: Int = DEFAULT_MAX_PENDING): Boolean =
        pendingDatagrams >= maxPending

    /**
     * Admit a whole frame atomically.
     * - Empty queue: always accept one full frame (up to [MAX_FRAME_DATAGRAMS]).
     * - Keyframe: never reject for budget alone (IDR must reach the decoder).
     * - Otherwise: only if pending + frame fits under [maxPending].
     */
    fun shouldAdmitFrame(
        pendingDatagrams: Int,
        frameDatagrams: Int,
        maxPending: Int = DEFAULT_MAX_PENDING,
        keyframe: Boolean,
    ): Boolean {
        if (frameDatagrams <= 0 || frameDatagrams > MAX_FRAME_DATAGRAMS) return false
        if (pendingDatagrams == 0) return true
        if (keyframe) return true
        return pendingDatagrams + frameDatagrams <= maxPending
    }

    fun wouldRejectFrame(
        pendingDatagrams: Int,
        frameDatagrams: Int,
        maxPending: Int = DEFAULT_MAX_PENDING,
        keyframe: Boolean = false,
    ): Boolean = !shouldAdmitFrame(pendingDatagrams, frameDatagrams, maxPending, keyframe)

    fun paceDelayMs(batchBytes: Int, bytesPerMs: Int): Double {
        val budget = maxOf(1, bytesPerMs)
        return (batchBytes.toDouble() / budget.toDouble()).coerceIn(0.05, 4.0)
    }

    fun bytesPerMs(encodeBitrate: Int, fecPct: Int): Int {
        val wireBps = encodeBitrate.toDouble() * (1.0 + fecPct.toDouble() / 100.0)
        return maxOf(1, (wireBps / 8.0 / 1000.0).toInt())
    }
}
