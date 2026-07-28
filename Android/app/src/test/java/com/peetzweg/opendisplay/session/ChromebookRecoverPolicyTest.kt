package com.peetzweg.opendisplay.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChromebookRecoverPolicyTest {
    @Test
    fun allowForceReconnect_beforeFirstPaint_allowsStuckGreenTear() {
        assertTrue(ChromebookRecoverPolicy.allowForceReconnect(hasPaintedThisConnection = false))
    }

    @Test
    fun allowForceReconnect_afterFirstPaint_blocksMidSessionTear() {
        assertFalse(ChromebookRecoverPolicy.allowForceReconnect(hasPaintedThisConnection = true))
    }

    @Test
    fun udpDecodeError_requestsKeyframeWithoutTearingTcp() {
        val action = ChromebookRecoverPolicy.onDecodeError(transport = "udp", consecutiveErrors = 2)
        assertEquals(ChromebookRecoverPolicy.Action.RequestKeyframe, action)
    }
}
