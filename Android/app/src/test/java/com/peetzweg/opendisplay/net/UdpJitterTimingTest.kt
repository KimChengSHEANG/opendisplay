package com.peetzweg.opendisplay.net

import org.junit.Assert.assertEquals
import org.junit.Test

class UdpJitterTimingTest {
    @Test
    fun defaults_are_sunshine_small_jitter() {
        assertEquals(16L, UdpJitterTiming.TARGET_DELAY_MS)
        assertEquals(60L, UdpJitterTiming.MAX_DELAY_MS)
    }

    @Test
    fun maxDelay_scales_with_rtt_and_clamps() {
        assertEquals(60L, UdpJitterTiming.maxDelayMs(null))
        assertEquals(40L, UdpJitterTiming.maxDelayMs(5.0)) // 5+20=25 → clamp min 40
        assertEquals(50L, UdpJitterTiming.maxDelayMs(30.0)) // 30+20
        assertEquals(80L, UdpJitterTiming.maxDelayMs(200.0)) // 220 → clamp max 80
    }
}
