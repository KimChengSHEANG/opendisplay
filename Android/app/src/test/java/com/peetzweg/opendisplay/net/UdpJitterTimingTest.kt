package com.peetzweg.opendisplay.net

import org.junit.Assert.assertEquals
import org.junit.Test

class UdpJitterTimingTest {
    @Test
    fun defaults_leave_room_for_wifi_nack_rtt() {
        assertEquals(50L, UdpJitterTiming.TARGET_DELAY_MS)
        assertEquals(120L, UdpJitterTiming.MAX_DELAY_MS)
    }

    @Test
    fun maxDelay_scales_with_rtt_and_clamps() {
        assertEquals(120L, UdpJitterTiming.maxDelayMs(null))
        assertEquals(80L, UdpJitterTiming.maxDelayMs(10.0)) // 2*10=20 → clamp min 80
        assertEquals(100L, UdpJitterTiming.maxDelayMs(50.0))
        assertEquals(200L, UdpJitterTiming.maxDelayMs(200.0)) // 400 → clamp max 200
    }
}
