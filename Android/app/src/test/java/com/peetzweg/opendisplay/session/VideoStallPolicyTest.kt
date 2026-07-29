package com.peetzweg.opendisplay.session

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoStallPolicyTest {
    @Test
    fun freshFrames_noAction() {
        assertEquals(
            VideoStallPolicy.Action.None,
            VideoStallPolicy.action(
                ageMs = 500,
                hasPaintedThisConnection = true,
                connected = true,
            ),
        )
    }

    @Test
    fun quietTwoSeconds_requestsKeyframe() {
        assertEquals(
            VideoStallPolicy.Action.RequestKeyframe,
            VideoStallPolicy.action(
                ageMs = VideoStallPolicy.KEYFRAME_AFTER_MS,
                hasPaintedThisConnection = true,
                connected = true,
            ),
        )
    }

    @Test
    fun quietFiveSeconds_rebuildsCodec() {
        assertEquals(
            VideoStallPolicy.Action.RebuildCodec,
            VideoStallPolicy.action(
                ageMs = VideoStallPolicy.REBUILD_AFTER_MS,
                hasPaintedThisConnection = true,
                connected = true,
            ),
        )
    }

    @Test
    fun beforeFirstPaint_orDisconnected_skips() {
        assertEquals(
            VideoStallPolicy.Action.None,
            VideoStallPolicy.action(
                ageMs = 10_000,
                hasPaintedThisConnection = false,
                connected = true,
            ),
        )
        assertEquals(
            VideoStallPolicy.Action.None,
            VideoStallPolicy.action(
                ageMs = 10_000,
                hasPaintedThisConnection = true,
                connected = false,
            ),
        )
        assertEquals(
            VideoStallPolicy.Action.None,
            VideoStallPolicy.action(
                ageMs = null,
                hasPaintedThisConnection = true,
                connected = true,
            ),
        )
    }
}
