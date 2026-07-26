package com.peetzweg.opendisplay.sleep

import com.peetzweg.opendisplay.wire.WireMessage

/**
 * Host-sleep / local-lock state machine. Pure Kotlin (no `android.*`) so it's
 * unit-testable; `MainActivity` wires the actual window brightness /
 * keep-screen-on flag / lock broadcasts / [com.peetzweg.opendisplay.session.ReceiverSession]
 * lifecycle to the callbacks below. Mirrors `PhoneReceiver.swift`:
 *
 * - [onHostSleeping] ~ `beginHostDisplayOff`: the Mac's own display slept or
 *   locked — blank this panel (black UI + brightness ~10%) but **keep
 *   listening**; the Mac owns the reconnect and may dial back in any moment.
 *   On Chromebook, that level also drives the ChromeOS panel backlight via
 *   [PanelBacklight] / `Settings.System.SCREEN_BRIGHTNESS`.
 * - [onConnected]/[wake] ~ `endHostDisplayOff`: a fresh connection, or the
 *   user tapping the blanked panel, undoes the blank (brightness restored).
 * - [onDeviceWillLock]/[onDeviceUnlocked] ~ screen-off / unlock (or
 *   `deviceWillLock`/`sceneDidActivate` on iOS): the panel went dark, so
 *   nobody can see the stream — announce `sleeping` and stop accepting until
 *   the screen is back, or a Mac reconnect would rebuild the display before
 *   anyone can see it. Chromebooks often have no Android keyguard, so this
 *   is driven by `ACTION_SCREEN_OFF` / `ACTION_SCREEN_ON`, not lock alone.
 * - [onAppQuitting] ~ `appWillTerminate`: announce `closing` so the Mac ends
 *   the session immediately instead of waiting out its silence grace.
 */
class HostSleepController(
    private val sendControl: (Map<String, Any>) -> Unit,
    private val setBrightness: (Float?) -> Unit,
    private val setKeepScreenOn: (Boolean) -> Unit,
    private val stopAccepting: () -> Unit,
    private val resumeAccepting: () -> Unit,
) {
    /** True while the panel is blanked for a Mac-side sleep; drives the black overlay in Compose. */
    var hostDisplayOff = false
        private set

    private var locallyLocked = false

    fun onHostSleeping() {
        hostDisplayOff = true
        setKeepScreenOn(false)
        setBrightness(HOST_SLEEP_BRIGHTNESS)
    }

    fun onConnected() = wake()

    /** User tapped the blanked panel: restore brightness without ending the wait for the Mac. */
    fun wake() {
        if (!hostDisplayOff) return
        hostDisplayOff = false
        setBrightness(null)
    }

    fun onDeviceWillLock() {
        if (locallyLocked) return
        locallyLocked = true
        sendControl(mapOf("type" to WireMessage.sleeping))
        stopAccepting()
    }

    fun onDeviceUnlocked() {
        if (!locallyLocked) return
        locallyLocked = false
        resumeAccepting()
    }

    fun onAppQuitting() {
        sendControl(mapOf("type" to WireMessage.closing))
    }

    companion object {
        /** Dim level while the Mac display is off — 10%, not fully black. */
        const val HOST_SLEEP_BRIGHTNESS = 0.10f
    }
}
