package com.peetzweg.opendisplay.session

import org.junit.Assert.assertEquals
import org.junit.Test

class VirtualDisplayRetryPolicyTest {
    @Test
    fun backoff_rampsThenCaps() {
        assertEquals(0.0, VirtualDisplayRetryPolicy.sleepSecondsBeforeAttempt(0), 0.0)
        assertEquals(0.25, VirtualDisplayRetryPolicy.sleepSecondsBeforeAttempt(1), 0.0)
        assertEquals(0.5, VirtualDisplayRetryPolicy.sleepSecondsBeforeAttempt(2), 0.0)
        assertEquals(1.0, VirtualDisplayRetryPolicy.sleepSecondsBeforeAttempt(3), 0.0)
        assertEquals(1.5, VirtualDisplayRetryPolicy.sleepSecondsBeforeAttempt(4), 0.0)
        assertEquals(1.5, VirtualDisplayRetryPolicy.sleepSecondsBeforeAttempt(7), 0.0)
    }
}
