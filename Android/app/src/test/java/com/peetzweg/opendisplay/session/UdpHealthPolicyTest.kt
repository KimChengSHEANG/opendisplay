package com.peetzweg.opendisplay.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpHealthPolicyTest {
    @Test
    fun requests_keyframe_after_late_drops_or_incomplete() {
        assertTrue(UdpHealthPolicy.shouldRequestKeyframe(lateFrames = 2, incompleteFrames = 0, decodeErrors = 0))
        assertTrue(UdpHealthPolicy.shouldRequestKeyframe(lateFrames = 0, incompleteFrames = 1, decodeErrors = 0))
        assertFalse(UdpHealthPolicy.shouldRequestKeyframe(lateFrames = 0, incompleteFrames = 0, decodeErrors = 0))
    }

    @Test
    fun falls_back_to_tcp_on_sustained_high_loss() {
        assertFalse(UdpHealthPolicy.shouldFallbackToTcp(lossPct = 10.0, consecutiveBadWindows = 1))
        assertTrue(UdpHealthPolicy.shouldFallbackToTcp(lossPct = 25.0, consecutiveBadWindows = 3))
    }

    @Test
    fun falls_back_after_three_bad_qos_windows() {
        val policy = UdpHealthPolicy.FallbackTracker()
        repeat(3) { policy.noteWindow(lossPct = 30.0) }
        assertEquals("tcp", policy.preferredVideoTransport())
    }

    @Test
    fun qos_map_contains_required_keys() {
        val map = UdpHealthPolicy.qosMap(
            lossPct = 4.0,
            jitterMs = 12.0,
            nackRate = 0.05,
            lateFrames = 1,
            fecRecoveries = 3,
        )
        assertEquals("qos", map["type"])
        assertEquals(4.0, map["lossPct"])
        assertEquals(12.0, map["jitterMs"])
    }
}
