package com.peetzweg.opendisplay.session

/** Budgets for Mac→Chromebook connect feel (wall-clock, device-side). */
object ConnectTimingPolicy {
    /** Dial accept → first decoded present should usually beat this on WiFi. */
    const val SLOW_TTFF_MS = 3_000L

    fun isSlowTtff(dialToPaintMs: Long): Boolean = dialToPaintMs > SLOW_TTFF_MS
}
