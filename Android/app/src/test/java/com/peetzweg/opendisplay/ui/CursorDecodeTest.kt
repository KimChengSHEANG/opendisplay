package com.peetzweg.opendisplay.ui

import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/**
 * JVM unit tests can't exercise [android.graphics.BitmapFactory], so we only
 * assert the base64-reject path here. Valid PNG decoding is covered on-device.
 */
class CursorDecodeTest {
    @Test
    fun decodeCursorPng_invalidBase64() {
        assertNull(decodeCursorPng("not-base64!!!"))
    }

    @Test
    fun decodeCursorPng_notAnImage() {
        // Valid base64 that isn't a PNG — BitmapFactory returns null on device;
        // under JVM unit tests the Android stub may throw or return null.
        val junk = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        try {
            assertNull(decodeCursorPng(junk))
        } catch (_: RuntimeException) {
            // android.graphics stubs aren't mocked in JVM tests — acceptable.
        }
    }
}
