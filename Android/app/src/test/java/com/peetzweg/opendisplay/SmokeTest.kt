package com.peetzweg.opendisplay

import org.junit.Assert.assertEquals
import org.junit.Test

class SmokeTest {
    @Test
    fun packageName_isOpenDisplay() {
        assertEquals("com.peetzweg.opendisplay", BuildConfig.APPLICATION_ID)
    }
}
