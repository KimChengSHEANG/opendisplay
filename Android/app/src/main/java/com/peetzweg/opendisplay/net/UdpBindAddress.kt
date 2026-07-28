package com.peetzweg.opendisplay.net

/**
 * UDP listen address preference for [UdpVideoReceiver].
 *
 * Prefer the IPv6 wildcard first so Chromebook WiFi (often TCP-over-IPv6)
 * can receive Mac UDP video. Fall back to IPv4-only when IPv6 bind fails.
 */
object UdpBindAddress {
    fun bindHostCandidates(): List<String> = listOf("::", "0.0.0.0")
}
