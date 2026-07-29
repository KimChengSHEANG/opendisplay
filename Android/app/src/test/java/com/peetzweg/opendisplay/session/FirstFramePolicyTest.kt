package com.peetzweg.opendisplay.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FirstFramePolicyTest {
    @Test
    fun chromebook_getsFollowUpIdrDelay() {
        assertEquals(250L, FirstFramePolicy.followUpIdrDelayMs(deviceKind = "Chromebook"))
        assertNull(FirstFramePolicy.followUpIdrDelayMs(deviceKind = "iPhone"))
        assertNull(FirstFramePolicy.followUpIdrDelayMs(deviceKind = null))
    }
}
