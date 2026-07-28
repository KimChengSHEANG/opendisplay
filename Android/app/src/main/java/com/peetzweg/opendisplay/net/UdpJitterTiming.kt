package com.peetzweg.opendisplay.net

/**
 * WiFi UDP jitter / NACK playout window (Sunshine-style: small hold).
 *
 * Playout delay is measured from **arrival** of a complete frame, not Mac
 * capture timestamps (clock skew was adding tens of ms of fake latency).
 */
object UdpJitterTiming {
    /** ~1 frame at 60fps — smooth WiFi jitter without trailing the cursor. */
    const val TARGET_DELAY_MS = 16L
    /** Default incomplete/NACK wait when RTT unknown. */
    const val MAX_DELAY_MS = 60L
    const val MIN_MAX_DELAY_MS = 40L
    const val MAX_MAX_DELAY_MS = 80L

    /** Incomplete deadline ≈ RTT + 20ms, clamped for Chromebook WiFi. */
    fun maxDelayMs(rttMs: Double?): Long {
        if (rttMs == null || rttMs <= 0) return MAX_DELAY_MS
        return (rttMs + 20.0).toLong().coerceIn(MIN_MAX_DELAY_MS, MAX_MAX_DELAY_MS)
    }
}
