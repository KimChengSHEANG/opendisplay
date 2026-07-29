package com.peetzweg.opendisplay.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectTimingPolicyTest {
    @Test
    fun slowTtff_flagsAboveBudget() {
        assertFalse(ConnectTimingPolicy.isSlowTtff(dialToPaintMs = 2_500))
        assertTrue(ConnectTimingPolicy.isSlowTtff(dialToPaintMs = ConnectTimingPolicy.SLOW_TTFF_MS + 1))
    }
}
