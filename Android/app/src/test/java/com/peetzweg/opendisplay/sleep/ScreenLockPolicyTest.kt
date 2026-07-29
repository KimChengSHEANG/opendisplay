package com.peetzweg.opendisplay.sleep

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenLockPolicyTest {
    @Test
    fun screenOff_alwaysStops_evenWithoutKeyguard() {
        assertTrue(ScreenLockPolicy.shouldStopOnScreenOff())
    }

    @Test
    fun screenOn_resumesWithoutSecureKeyguard() {
        assertTrue(ScreenLockPolicy.shouldResumeOnScreenOn(isDeviceSecure = false))
        assertFalse(ScreenLockPolicy.shouldResumeOnScreenOn(isDeviceSecure = true))
    }
}
