package com.peetzweg.opendisplay.sleep

import com.peetzweg.opendisplay.wire.WireMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HostSleepControllerTest {
    private val sent = mutableListOf<Map<String, Any>>()
    private val brightnessCalls = mutableListOf<Float?>()
    private val keepScreenOnCalls = mutableListOf<Boolean>()
    private var acceptingStopped = 0
    private var acceptingResumed = 0
    private lateinit var controller: HostSleepController

    @Before
    fun setUp() {
        sent.clear()
        brightnessCalls.clear()
        keepScreenOnCalls.clear()
        acceptingStopped = 0
        acceptingResumed = 0
        controller = HostSleepController(
            sendControl = { sent += it },
            setBrightness = { brightnessCalls += it },
            setKeepScreenOn = { keepScreenOnCalls += it },
            stopAccepting = { acceptingStopped++ },
            resumeAccepting = { acceptingResumed++ },
        )
    }

    @Test
    fun onHostSleeping_blanksPanelAndClearsKeepScreenOnButKeepsListening() {
        controller.onHostSleeping()

        assertTrue(controller.hostDisplayOff)
        assertEquals(listOf(HostSleepController.HOST_SLEEP_BRIGHTNESS), brightnessCalls)
        assertEquals(listOf(false), keepScreenOnCalls)
        assertEquals(0, acceptingStopped)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun onConnected_afterHostSleeping_restoresBrightnessAndClearsOverlay() {
        controller.onHostSleeping()
        controller.onConnected()

        assertFalse(controller.hostDisplayOff)
        assertEquals(listOf(HostSleepController.HOST_SLEEP_BRIGHTNESS, null), brightnessCalls)
    }

    @Test
    fun onConnected_withoutPriorHostSleeping_isNoOp() {
        controller.onConnected()

        assertFalse(controller.hostDisplayOff)
        assertTrue(brightnessCalls.isEmpty())
    }

    @Test
    fun wake_restoresBrightnessLikeReconnect() {
        controller.onHostSleeping()
        controller.wake()

        assertFalse(controller.hostDisplayOff)
        assertEquals(listOf(HostSleepController.HOST_SLEEP_BRIGHTNESS, null), brightnessCalls)
    }

    @Test
    fun onDeviceWillLock_announcesSleepingAndStopsAccepting() {
        controller.onDeviceWillLock()

        assertEquals(1, sent.size)
        assertEquals(WireMessage.sleeping, sent[0]["type"])
        assertEquals(1, acceptingStopped)
        assertEquals(0, acceptingResumed)
    }

    @Test
    fun onDeviceWillLock_isIdempotentUntilUnlocked() {
        controller.onDeviceWillLock()
        controller.onDeviceWillLock()

        assertEquals(1, sent.size)
        assertEquals(1, acceptingStopped)
    }

    @Test
    fun onDeviceUnlocked_afterLock_resumesAccepting() {
        controller.onDeviceWillLock()
        controller.onDeviceUnlocked()

        assertEquals(1, acceptingResumed)
    }

    @Test
    fun onDeviceUnlocked_withoutPriorLock_isNoOp() {
        controller.onDeviceUnlocked()

        assertEquals(0, acceptingResumed)
    }

    @Test
    fun onAppQuitting_announcesClosing() {
        controller.onAppQuitting()

        assertEquals(1, sent.size)
        assertEquals(WireMessage.closing, sent[0]["type"])
    }
}
