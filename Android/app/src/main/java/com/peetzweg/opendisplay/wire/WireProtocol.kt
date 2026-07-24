package com.peetzweg.opendisplay.wire

object WireProtocol {
    const val version = 2
    const val minSupportedPeer = 1
    const val assumedWhenAbsent = 1
}

object WireMessage {
    const val welcome = "welcome"
    const val updateRequired = "updateRequired"
    const val sleeping = "sleeping"
    const val closing = "closing"
    const val hostSleeping = "hostSleeping"
}
