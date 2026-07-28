package com.peetzweg.opendisplay.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpBindAddressTest {
    @Test
    fun prefers_ipv6_wildcard_before_ipv4_so_chromebook_wifi_receives_udp() {
        // Chromebook ARC often dials TCP over global IPv6; MacSender mirrors
        // that remote endpoint for UDP. An IPv4-only bind (0.0.0.0) never
        // sees those datagrams → black screen + forcePeerReconnect loop.
        val hosts = UdpBindAddress.bindHostCandidates()
        assertEquals("::", hosts.first())
        assertTrue(hosts.contains("0.0.0.0"))
        assertEquals(listOf("::", "0.0.0.0"), hosts)
    }
}
