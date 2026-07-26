package com.peetzweg.opendisplay.sleep

import android.content.Context
import android.provider.Settings
import android.view.Window
import android.view.WindowManager

/**
 * Dim / restore the panel for host-sleep.
 *
 * Phones: [WindowManager.LayoutParams.screenBrightness] is enough (same idea as
 * iOS `UIScreen.brightness`).
 *
 * Chromebooks: ARC window brightness often does **not** drive the ChromeOS
 * backlight — write [Settings.System.SCREEN_BRIGHTNESS] too so powerd can dim
 * the panel (and restore the previous level on wake / reconnect).
 */
class PanelBacklight(
    private val window: Window,
    private val context: Context,
    private val chromebook: Boolean,
) {
    private var savedWindowBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    private var savedSystemBrightness: Int? = null
    private var savedSystemMode: Int? = null
    private var dimmed = false

    /** @param level `0f`…`1f` to dim, or `null` to restore the pre-dim values. */
    fun set(level: Float?) {
        if (level == null) {
            restore()
            return
        }
        dim(level)
    }

    /** Full brightness while streaming (Chromebook ARC windows run dim otherwise). */
    fun pinFull() {
        val attrs = window.attributes
        if (!dimmed) {
            savedWindowBrightness = attrs.screenBrightness
            if (chromebook) snapshotSystemBrightness()
        }
        attrs.screenBrightness = 1f
        window.attributes = attrs
        if (chromebook) writeSystemBrightness(255)
    }

    private fun dim(level: Float) {
        val attrs = window.attributes
        if (!dimmed) {
            savedWindowBrightness = attrs.screenBrightness
            if (chromebook) snapshotSystemBrightness()
        }
        dimmed = true
        val clamped = level.coerceIn(0f, 1f)
        attrs.screenBrightness = clamped
        window.attributes = attrs
        if (chromebook) {
            writeSystemBrightness((clamped * 255f).toInt())
        }
    }

    private fun restore() {
        if (!dimmed && savedSystemBrightness == null) {
            // Still clear a Chromebook pin-full override when leaving the stream.
            val attrs = window.attributes
            attrs.screenBrightness = savedWindowBrightness
            window.attributes = attrs
            return
        }
        dimmed = false
        val attrs = window.attributes
        attrs.screenBrightness = savedWindowBrightness
        window.attributes = attrs
        restoreSystemBrightness()
    }

    private fun snapshotSystemBrightness() {
        if (savedSystemBrightness != null) return
        try {
            val cr = context.contentResolver
            savedSystemBrightness = Settings.System.getInt(
                cr, Settings.System.SCREEN_BRIGHTNESS, 128
            )
            savedSystemMode = Settings.System.getInt(
                cr,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
            )
        } catch (_: Throwable) {
            savedSystemBrightness = null
            savedSystemMode = null
        }
    }

    private fun writeSystemBrightness(value: Int) {
        try {
            val cr = context.contentResolver
            Settings.System.putInt(
                cr,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, value.coerceIn(0, 255))
        } catch (_: SecurityException) {
            // WRITE_SETTINGS not granted — window brightness still applied.
        } catch (_: Throwable) {
            // Chromebook builds without the setting — ignore.
        }
    }

    private fun restoreSystemBrightness() {
        val level = savedSystemBrightness ?: return
        val mode = savedSystemMode
        savedSystemBrightness = null
        savedSystemMode = null
        try {
            val cr = context.contentResolver
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, level.coerceIn(0, 255))
            if (mode != null) {
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, mode)
            }
        } catch (_: Throwable) {
            // Best-effort restore.
        }
    }
}
