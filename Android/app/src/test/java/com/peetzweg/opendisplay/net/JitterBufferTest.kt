package com.peetzweg.opendisplay.net

import org.junit.Assert.assertEquals
import org.junit.Test

class JitterBufferTest {
    private var now = 0L

    @Test
    fun releases_in_order_after_target_delay() {
        val released = mutableListOf<Long>()
        val buffer = JitterBuffer(
            targetDelayMs = 20,
            maxDelayMs = 40,
            nowMs = { now },
            onRelease = { released += it.frameId },
            onDropLate = {},
            onIncomplete = { _, _ -> },
        )
        buffer.offer(
            JitterBuffer.Frame(
                frameId = 2, complete = true, captureMs = 20,
                sendMs = 21, annexB = byteArrayOf(2), isKeyframe = false, seqs = intArrayOf(2),
            )
        )
        buffer.offer(
            JitterBuffer.Frame(
                frameId = 1, complete = true, captureMs = 10,
                sendMs = 11, annexB = byteArrayOf(1), isKeyframe = true, seqs = intArrayOf(1),
            )
        )
        now = 30
        buffer.drain()
        assertEquals(listOf(1L, 2L), released)
    }

    @Test
    fun drops_incomplete_after_max_delay_and_reports_missing() {
        val incomplete = mutableListOf<Pair<Long, IntArray>>()
        val buffer = JitterBuffer(
            targetDelayMs = 20,
            maxDelayMs = 40,
            nowMs = { now },
            onRelease = {},
            onDropLate = {},
            onIncomplete = { id, missing -> incomplete += id to missing },
        )
        buffer.offerPartial(frameId = 5, missingSeqs = intArrayOf(50, 51), firstSeenMs = 0)
        now = 41
        buffer.drain()
        assertEquals(5L, incomplete.single().first)
        assertEquals(listOf(50, 51), incomplete.single().second.toList())
    }

    @Test
    fun drops_complete_frame_that_arrives_too_late() {
        val dropped = mutableListOf<Long>()
        val released = mutableListOf<Long>()
        val buffer = JitterBuffer(
            targetDelayMs = 20,
            maxDelayMs = 40,
            nowMs = { now },
            onRelease = { released += it.frameId },
            onDropLate = { dropped += it },
            onIncomplete = { _, _ -> },
        )
        // Playout head already at capture 100; a complete frame with capture 10 is late.
        buffer.advancePlayoutHeadForTest(100)
        buffer.offer(
            JitterBuffer.Frame(
                frameId = 1, complete = true, captureMs = 10,
                sendMs = 11, annexB = byteArrayOf(1), isKeyframe = false, seqs = intArrayOf(1),
            )
        )
        now = 200
        buffer.drain()
        assertEquals(listOf(1L), dropped)
        assertEquals(emptyList<Long>(), released)
    }
}
