package com.peetzweg.opendisplay.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpSendBudgetTest {
    @Test
    fun shouldDropFrame_whenPendingAtCap() {
        assertTrue(UdpSendBudget.shouldNetDrop(pendingDatagrams = 64, maxPending = 64))
        assertFalse(UdpSendBudget.shouldNetDrop(pendingDatagrams = 10, maxPending = 64))
    }

    @Test
    fun paceDelayMs_scalesWithBatchBytes() {
        // 12_000 bytes at 3_000 bytes/ms budget → 4ms
        assertEquals(4.0, UdpSendBudget.paceDelayMs(batchBytes = 12_000, bytesPerMs = 3_000), 0.01)
    }
}
