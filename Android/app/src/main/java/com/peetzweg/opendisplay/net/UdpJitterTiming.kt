package com.peetzweg.opendisplay.net

/**
 * WiFi UDP jitter / reorder playout window (Sunshine-style: small hold).
 *
 * Incomplete wait is only for late shards / FEC assembly — not a packet-NACK
 * RTT budget. Playout delay is measured from arrival of a complete frame.
 */
object UdpJitterTiming {
    const val TARGET_DELAY_MS = 16L
    /** Default incomplete/reorder give-up when RTT unknown. */
    const val MAX_DELAY_MS = 32L
    const val MIN_MAX_DELAY_MS = 24L
    const val MAX_MAX_DELAY_MS = 40L

    /** Incomplete deadline ≈ half RTT, clamped — enough for reorder, not retransmit. */
    fun maxDelayMs(rttMs: Double?): Long {
        if (rttMs == null || rttMs <= 0) return MAX_DELAY_MS
        return (rttMs * 0.5).toLong().coerceIn(MIN_MAX_DELAY_MS, MAX_MAX_DELAY_MS)
    }
}
