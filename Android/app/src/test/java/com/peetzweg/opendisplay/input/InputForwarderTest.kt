package com.peetzweg.opendisplay.input

import org.junit.Assert.assertEquals
import org.junit.Test

class InputForwarderTest {

    @Test
    fun normalize_center_isHalfHalf() {
        val (x, y) = InputForwarder.normalize(50f, 100f, 100, 200)
        assertEquals(0.5, x, 1e-9)
        assertEquals(0.5, y, 1e-9)
    }

    @Test
    fun normalize_edges_clampToZeroAndOne() {
        val (negX, negY) = InputForwarder.normalize(-10f, -10f, 100, 200)
        assertEquals(0.0, negX, 1e-9)
        assertEquals(0.0, negY, 1e-9)

        val (overX, overY) = InputForwarder.normalize(150f, 300f, 100, 200)
        assertEquals(1.0, overX, 1e-9)
        assertEquals(1.0, overY, 1e-9)
    }

    @Test
    fun normalize_zeroSizeView_returnsCenterFallback() {
        val (x, y) = InputForwarder.normalize(10f, 10f, 0, 0)
        assertEquals(0.5, x, 1e-9)
        assertEquals(0.5, y, 1e-9)
    }

    @Test
    fun down_sendsTouchBeganWithNormalizedCoords() {
        val sent = mutableListOf<Map<String, Any>>()
        val forwarder = InputForwarder { sent += it }

        forwarder.down(25f, 50f, 100, 100)

        assertEquals(1, sent.size)
        assertEquals(mapOf("type" to "touch", "phase" to "began", "x" to 0.25, "y" to 0.5), sent[0])
    }

    @Test
    fun moveThenUp_sendsMovedThenEndedPhases() {
        val sent = mutableListOf<Map<String, Any>>()
        val forwarder = InputForwarder { sent += it }

        forwarder.down(0f, 0f, 100, 100)
        forwarder.move(50f, 50f, 100, 100)
        forwarder.up(100f, 100f, 100, 100)

        assertEquals(listOf("began", "moved", "ended"), sent.map { it["phase"] })
        assertEquals(1.0, sent[2]["x"])
        assertEquals(1.0, sent[2]["y"])
    }

    @Test
    fun cancel_sendsCancelledPhase() {
        val sent = mutableListOf<Map<String, Any>>()
        val forwarder = InputForwarder { sent += it }

        forwarder.down(10f, 10f, 100, 100)
        forwarder.cancel(10f, 10f, 100, 100)

        assertEquals(listOf("began", "cancelled"), sent.map { it["phase"] })
    }

    @Test
    fun secondPointerDown_cancelsInFlightSingleTouch() {
        val sent = mutableListOf<Map<String, Any>>()
        val forwarder = InputForwarder { sent += it }

        forwarder.down(20f, 40f, 100, 100)
        forwarder.secondPointerDown(50f, 50f)

        assertEquals(listOf("began", "cancelled"), sent.map { it["phase"] })
        assertEquals(0.2, sent[1]["x"])
        assertEquals(0.4, sent[1]["y"])
    }

    @Test
    fun moveAndUp_areIgnoredWhileTwoFingerGestureActive() {
        val sent = mutableListOf<Map<String, Any>>()
        val forwarder = InputForwarder { sent += it }

        forwarder.down(0f, 0f, 100, 100)
        forwarder.secondPointerDown(10f, 10f)
        forwarder.move(20f, 20f, 100, 100)
        forwarder.up(30f, 30f, 100, 100)

        assertEquals(2, sent.size)
        assertEquals(listOf("began", "cancelled"), sent.map { it["phase"] })
    }

    @Test
    fun twoFingerMove_sendsScrollDeltaSinceLastCall() {
        val sent = mutableListOf<Map<String, Any>>()
        val forwarder = InputForwarder { sent += it }

        forwarder.down(0f, 0f, 100, 100)
        forwarder.secondPointerDown(10f, 100f)
        forwarder.twoFingerMove(15f, 80f)
        forwarder.twoFingerMove(15f, 60f)

        val scrolls = sent.filter { it["type"] == "scroll" }
        assertEquals(2, scrolls.size)
        assertEquals(mapOf("type" to "scroll", "dx" to 5.0, "dy" to -20.0), scrolls[0])
        assertEquals(mapOf("type" to "scroll", "dx" to 0.0, "dy" to -20.0), scrolls[1])
    }

    @Test
    fun twoFingerMove_noOpBeforeSecondPointerDown() {
        val sent = mutableListOf<Map<String, Any>>()
        val forwarder = InputForwarder { sent += it }

        forwarder.down(0f, 0f, 100, 100)
        forwarder.twoFingerMove(15f, 80f)

        assertEquals(1, sent.size)
        assertEquals("began", sent[0]["phase"])
    }

    @Test
    fun secondPointerUp_thenFinalUp_isIgnoredUntilFreshDown() {
        val sent = mutableListOf<Map<String, Any>>()
        val forwarder = InputForwarder { sent += it }

        forwarder.down(0f, 0f, 100, 100)
        forwarder.secondPointerDown(10f, 10f)
        forwarder.secondPointerUp()
        forwarder.up(5f, 5f, 100, 100)

        assertEquals(listOf("began", "cancelled"), sent.map { it["phase"] })

        forwarder.down(1f, 1f, 100, 100)
        assertEquals(listOf("began", "cancelled", "began"), sent.map { it["phase"] })
    }

    @Test
    fun movedSamples_sendsEveryPointThenPredicts() {
        val sent = mutableListOf<Map<String, Any>>()
        val forwarder = InputForwarder { sent += it }

        forwarder.down(0f, 0f, 100, 100)
        forwarder.movedSamples(listOf(10f to 10f, 20f to 20f), 100, 100)

        // 2 real moves + 1 predicted (half of last delta from 10→20)
        val moved = sent.filter { it["phase"] == "moved" }
        assertEquals(3, moved.size)
        assertEquals(0.1, moved[0]["x"])
        assertEquals(0.2, moved[1]["x"])
        assertEquals(0.25, moved[2]["x"] as Double, 1e-9)
        assertEquals(0.25, moved[2]["y"] as Double, 1e-9)
    }

    @Test
    fun wheel_sendsScrollPixels() {
        val sent = mutableListOf<Map<String, Any>>()
        val forwarder = InputForwarder { sent += it }

        forwarder.wheel(3f, -12f)

        assertEquals(1, sent.size)
        assertEquals(mapOf("type" to "scroll", "dx" to 3.0, "dy" to -12.0), sent[0])
    }

    @Test
    fun wheel_zeroDelta_isNoOp() {
        val sent = mutableListOf<Map<String, Any>>()
        val forwarder = InputForwarder { sent += it }
        forwarder.wheel(0f, 0f)
        assertEquals(0, sent.size)
    }

    @Test
    fun ensureReleased_sendsEndedOnlyWhenDown() {
        val sent = mutableListOf<Map<String, Any>>()
        val forwarder = InputForwarder { sent += it }

        forwarder.ensureReleased(10f, 10f, 100, 100)
        assertEquals(0, sent.size)

        forwarder.down(10f, 10f, 100, 100)
        forwarder.ensureReleased(20f, 20f, 100, 100)

        assertEquals(listOf("began", "ended"), sent.map { it["phase"] })
        assertEquals(0.2, sent[1]["x"])
        assertEquals(0.2, sent[1]["y"])
    }

    @Test
    fun down_isIdempotentWhileAlreadyDown() {
        val sent = mutableListOf<Map<String, Any>>()
        val forwarder = InputForwarder { sent += it }

        forwarder.down(10f, 10f, 100, 100)
        forwarder.down(50f, 50f, 100, 100)
        forwarder.up(50f, 50f, 100, 100)

        assertEquals(listOf("began", "ended"), sent.map { it["phase"] })
    }

    @Test
    fun up_withoutDown_isNoOp() {
        val sent = mutableListOf<Map<String, Any>>()
        val forwarder = InputForwarder { sent += it }
        forwarder.up(10f, 10f, 100, 100)
        assertEquals(0, sent.size)
    }
}
