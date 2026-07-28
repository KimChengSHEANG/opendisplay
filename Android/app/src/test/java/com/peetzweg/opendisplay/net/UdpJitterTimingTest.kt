package com.peetzweg.opendisplay.net

import org.junit.Assert.assertEquals
import org.junit.Test

class UdpJitterTimingTest {
    @Test
    fun defaults_are_sunshine_small_jitter() {
        assertEquals(16L, UdpJitterTiming.TARGET_DELAY_MS)
        assertEquals(32L, UdpJitterTiming.MAX_DELAY_MS)
    }

    @Test
    fun max_delay_is_reorder_window_not_nack_rtt() {
        assertEquals(32L, UdpJitterTiming.maxDelayMs(null))
        assertEquals(24L, UdpJitterTiming.maxDelayMs(10.0))
        assertEquals(40L, UdpJitterTiming.maxDelayMs(100.0))
        assertEquals(30L, UdpJitterTiming.maxDelayMs(60.0))
    }
}
