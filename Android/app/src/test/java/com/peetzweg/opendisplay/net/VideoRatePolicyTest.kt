package com.peetzweg.opendisplay.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoRatePolicyTest {
    @Test
    fun high_incomplete_rate_forces_keyframe_without_tcp_fallback() {
        val policy = VideoRatePolicy(initialBitrate = 24_000_000)
        val action = policy.next(lossPct = 1.0, jitterMs = 10.0, incompleteRate = 0.2)
        assertNull(action.bitrate)
        assertTrue(action.forceKeyframe)
        assertFalse(action.preferTcpNextSession)
    }

    @Test
    fun sustained_high_loss_prefers_tcp() {
        val policy = VideoRatePolicy(initialBitrate = 24_000_000)
        val action = policy.next(lossPct = 25.0, jitterMs = 10.0, incompleteRate = 0.0)
        assertTrue(action.forceKeyframe)
        assertTrue(action.preferTcpNextSession)
    }

    @Test
    fun low_loss_climbs_bitrate() {
        val policy = VideoRatePolicy(initialBitrate = 24_000_000)
        val action = policy.next(lossPct = 1.0, jitterMs = 10.0, incompleteRate = 0.0)
        assertEquals(26_000_000, action.bitrate)
        assertFalse(action.forceKeyframe)
    }
}
