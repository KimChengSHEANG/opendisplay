package com.peetzweg.opendisplay.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpSendBudgetTest {
    @Test
    fun shouldDropFrame_whenPendingAtCap() {
        assertTrue(UdpSendBudget.shouldNetDrop(pendingDatagrams = 256, maxPending = 256))
        assertFalse(UdpSendBudget.shouldNetDrop(pendingDatagrams = 10, maxPending = 256))
    }

    @Test
    fun admits_large_frame_when_queue_empty() {
        // ~80 data + 16 parity shards — previously rejected by a hard 64 cap.
        assertTrue(
            UdpSendBudget.shouldAdmitFrame(
                pendingDatagrams = 0,
                frameDatagrams = 96,
                maxPending = 256,
                keyframe = true,
            ),
        )
        assertFalse(
            UdpSendBudget.wouldRejectFrame(
                pendingDatagrams = 0,
                frameDatagrams = 96,
                maxPending = 256,
                keyframe = true,
            ),
        )
    }

    @Test
    fun rejects_non_key_when_pending_plus_datagrams_exceeds_cap() {
        assertTrue(
            UdpSendBudget.wouldRejectFrame(
                pendingDatagrams = 250,
                frameDatagrams = 15,
                maxPending = 256,
                keyframe = false,
            ),
        )
        assertFalse(
            UdpSendBudget.wouldRejectFrame(
                pendingDatagrams = 240,
                frameDatagrams = 14,
                maxPending = 256,
                keyframe = false,
            ),
        )
    }

    @Test
    fun never_rejects_keyframe_for_budget_when_backlogged() {
        assertTrue(
            UdpSendBudget.shouldAdmitFrame(
                pendingDatagrams = 200,
                frameDatagrams = 100,
                maxPending = 256,
                keyframe = true,
            ),
        )
    }

    @Test
    fun rejects_oversized_frame_beyond_rs_cap() {
        assertFalse(
            UdpSendBudget.shouldAdmitFrame(
                pendingDatagrams = 0,
                frameDatagrams = 256,
                maxPending = 256,
                keyframe = true,
            ),
        )
    }

    @Test
    fun paceDelayMs_scalesWithBatchBytes() {
        // 12_000 bytes at 3_000 bytes/ms budget → 4ms
        assertEquals(4.0, UdpSendBudget.paceDelayMs(batchBytes = 12_000, bytesPerMs = 3_000), 0.01)
    }
}
