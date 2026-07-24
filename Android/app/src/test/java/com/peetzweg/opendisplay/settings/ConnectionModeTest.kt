package com.peetzweg.opendisplay.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionModeTest {
    @Test
    fun fromRaw_knownValues() {
        assertEquals(ConnectionMode.Both, ConnectionMode.fromRaw("both"))
        assertEquals(ConnectionMode.Usb, ConnectionMode.fromRaw("usb"))
        assertEquals(ConnectionMode.Wifi, ConnectionMode.fromRaw("wifi"))
    }

    @Test
    fun fromRaw_unknownDefaultsToBoth() {
        assertEquals(ConnectionMode.Both, ConnectionMode.fromRaw(null))
        assertEquals(ConnectionMode.Both, ConnectionMode.fromRaw("nope"))
    }

    @Test
    fun advertisesWifi_usbIsOff() {
        assertTrue(ConnectionMode.Both.advertisesWifi)
        assertTrue(ConnectionMode.Wifi.advertisesWifi)
        assertFalse(ConnectionMode.Usb.advertisesWifi)
    }
}
