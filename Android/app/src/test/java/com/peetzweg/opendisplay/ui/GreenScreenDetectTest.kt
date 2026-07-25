package com.peetzweg.opendisplay.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GreenScreenDetectTest {
    @Test
    fun solidGreen_isDetected() {
        assertTrue(isVdaGreenPixel(0, 180, 0))
    }

    @Test
    fun darkGreenVda_isDetected() {
        assertTrue(isVdaGreenPixel(20, 100, 20))
    }

    @Test
    fun desktopGray_isNotGreen() {
        assertFalse(isVdaGreenPixel(120, 120, 120))
    }

    @Test
    fun black_isNotGreen() {
        assertFalse(isVdaGreenPixel(0, 0, 0))
    }

    @Test
    fun grassPhoto_lowSaturation_isNotGreen() {
        // Typical photo greens have more balanced channels.
        assertFalse(isVdaGreenPixel(40, 90, 50))
    }
}
