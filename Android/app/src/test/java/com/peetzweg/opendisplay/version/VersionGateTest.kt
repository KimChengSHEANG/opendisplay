package com.peetzweg.opendisplay.version

import com.peetzweg.opendisplay.wire.WireMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionGateTest {
    @Test
    fun welcome_withCurrentPv_isOk() {
        val gate = VersionGate()

        gate.onControl(mapOf("type" to WireMessage.welcome, "pv" to 2, "min" to 1))

        assertEquals(VersionGate.Status.Ok, gate.status)
    }

    @Test
    fun welcome_withPvBelowOurFloor_recommendsUpdatingTheMac() {
        val gate = VersionGate()

        gate.onControl(mapOf("type" to WireMessage.welcome, "pv" to 0))

        val status = gate.status
        assertTrue(status is VersionGate.Status.Recommended)
        assertEquals(VersionGate.OLD_MAC_MESSAGE, (status as VersionGate.Status.Recommended).update.message)
    }

    @Test
    fun welcome_withoutPv_assumesProtocolOne() {
        val gate = VersionGate()

        gate.onControl(mapOf("type" to WireMessage.welcome))

        // assumedWhenAbsent (1) meets minSupportedPeer (1) — compatible.
        assertEquals(VersionGate.Status.Ok, gate.status)
    }

    @Test
    fun updateRequired_isBlockingWithMessageAndStoreFromMac() {
        val gate = VersionGate()

        gate.onControl(
            mapOf(
                "type" to WireMessage.updateRequired,
                "message" to "This device is too old for this Mac.",
                "store" to "https://example.com/update",
            ),
        )

        val status = gate.status
        assertTrue(status is VersionGate.Status.Required)
        val update = (status as VersionGate.Status.Required).update
        assertEquals("This device is too old for this Mac.", update.message)
        assertEquals("https://example.com/update", update.url)
    }

    @Test
    fun updateRequired_withoutMessageOrStore_usesFallbacks() {
        val gate = VersionGate()

        gate.onControl(mapOf("type" to WireMessage.updateRequired))

        val status = gate.status
        assertTrue(status is VersionGate.Status.Required)
        val update = (status as VersionGate.Status.Required).update
        assertEquals(VersionGate.UPDATE_REQUIRED_FALLBACK, update.message)
        assertEquals(VersionGate.PROJECT_SITE_URL, update.url)
    }

    @Test
    fun updateRequired_overridesAnEarlierRecommendation() {
        val gate = VersionGate()
        gate.onControl(mapOf("type" to WireMessage.welcome, "pv" to 0))

        gate.onControl(mapOf("type" to WireMessage.updateRequired, "message" to "update now"))

        assertTrue(gate.status is VersionGate.Status.Required)
    }

    @Test
    fun unrelatedControlMessage_isIgnored() {
        val gate = VersionGate()

        gate.onControl(mapOf("type" to "ping"))

        assertEquals(VersionGate.Status.Ok, gate.status)
    }
}
