package com.peetzweg.opendisplay.net

/**
 * WiFi UDP jitter / NACK playout window.
 *
 * Prior 20/40ms deadlines fired before Chromebook WiFi RTT retransmits
 * arrived, causing incomplete → IDR storms worse than TCP head-of-line.
 */
object UdpJitterTiming {
    const val TARGET_DELAY_MS = 50L
    const val MAX_DELAY_MS = 120L
    const val MIN_MAX_DELAY_MS = 80L
    const val MAX_MAX_DELAY_MS = 200L

    /** Widen incomplete deadline from measured control RTT when available. */
    fun maxDelayMs(rttMs: Double?): Long {
        if (rttMs == null || rttMs <= 0) return MAX_DELAY_MS
        val fromRtt = (rttMs * 2.0).toLong()
        return fromRtt.coerceIn(MIN_MAX_DELAY_MS, MAX_MAX_DELAY_MS)
    }
}
