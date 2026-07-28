package com.peetzweg.opendisplay.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    @Test
    fun udpDecodeError_atFiveErrors_rebuildsCodec() {
        val action = ChromebookRecoverPolicy.onDecodeError(transport = "udp", consecutiveErrors = 5)
        assertEquals(ChromebookRecoverPolicy.Action.RebuildCodec, action)
    }

    @Test
    fun udpDecodeError_neverTearsSessionAtHighErrorCounts() {
        for (errors in listOf(10, 100)) {
            val action = ChromebookRecoverPolicy.onDecodeError(transport = "udp", consecutiveErrors = errors)
            assertNotEquals(ChromebookRecoverPolicy.Action.TearSession, action)
            assertEquals(ChromebookRecoverPolicy.Action.RebuildCodec, action)
        }
    }

    @Test
    fun tcpDecodeError_atTenErrors_tearsSession() {
        val action = ChromebookRecoverPolicy.onDecodeError(transport = "tcp", consecutiveErrors = 10)
        assertEquals(ChromebookRecoverPolicy.Action.TearSession, action)
    }
}
