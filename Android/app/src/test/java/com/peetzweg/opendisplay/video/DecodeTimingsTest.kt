package com.peetzweg.opendisplay.video

import org.junit.Assert.assertEquals
import org.junit.Test

class DecodeTimingsTest {
    private val ms = 1_000_000L // nanos per millisecond

    @Test
    fun pairsQueuedAndRenderedByPts() {
        val timings = DecodeTimings()
        timings.noteQueued(100L, 0L)
        timings.noteQueued(200L, 5 * ms)
        timings.noteRendered(100L, 12 * ms)
        timings.noteRendered(200L, 13 * ms)

        assertEquals(listOf(12.0, 8.0), timings.snapshot())
    }

    @Test
    fun renderedWithoutQueuedIsIgnored() {
        val timings = DecodeTimings()
        timings.noteRendered(999L, 10 * ms)
        assertEquals(emptyList<Double>(), timings.snapshot())
    }

    @Test
    fun sameFrameCountedOnlyOnce() {
        val timings = DecodeTimings()
        timings.noteQueued(100L, 0L)
        timings.noteRendered(100L, 10 * ms)
        timings.noteRendered(100L, 20 * ms)
        assertEquals(listOf(10.0), timings.snapshot())
    }

    @Test
    fun implausibleSamplesAreDropped() {
        val timings = DecodeTimings()
        timings.noteQueued(100L, 50 * ms)
        timings.noteRendered(100L, 10 * ms)          // negative
        timings.noteQueued(200L, 0L)
        timings.noteRendered(200L, 9_000 * ms)       // beyond the 5s ceiling
        assertEquals(emptyList<Double>(), timings.snapshot())
    }

    @Test
    fun samplesRingIsBounded() {
        val timings = DecodeTimings(capacity = 3)
        for (i in 1L..5L) {
            timings.noteQueued(i, 0L)
            timings.noteRendered(i, i * ms)
        }
        assertEquals(listOf(3.0, 4.0, 5.0), timings.snapshot())
    }

    @Test
    fun unrenderedFramesDoNotGrowUnbounded() {
        val timings = DecodeTimings(capacity = 3)
        for (i in 1L..10L) timings.noteQueued(i, 0L)
        // The oldest pending entries were evicted; only the newest survive.
        timings.noteRendered(1L, 5 * ms)
        timings.noteRendered(10L, 5 * ms)
        assertEquals(listOf(5.0), timings.snapshot())
    }

    @Test
    fun clearResetsBothBuffers() {
        val timings = DecodeTimings()
        timings.noteQueued(100L, 0L)
        timings.noteRendered(100L, 10 * ms)
        timings.clear()
        assertEquals(emptyList<Double>(), timings.snapshot())
        timings.noteRendered(100L, 20 * ms)
        assertEquals(emptyList<Double>(), timings.snapshot())
    }
}
