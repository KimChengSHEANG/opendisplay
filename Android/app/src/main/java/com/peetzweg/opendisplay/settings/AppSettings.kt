package com.peetzweg.opendisplay.settings

import android.content.Context

/**
 * How this receiver wants the Mac to find it — Android counterpart of the
 * USB vs WiFi paths described in iOS Settings "How to connect".
 *
 * Listening on :9000 is always on (both transports dial the same port).
 * Only Bonjour/NSD advertising changes:
 * - [Both] / [Wifi]: advertise on the LAN (Mac WiFi picker)
 * - [Usb]: advertise off — Mac connects via `adb forward` / USB only
 */
enum class ConnectionMode(val raw: String, val label: String) {
    Both("both", "USB & WiFi"),
    Usb("usb", "USB"),
    Wifi("wifi", "WiFi"),
    ;

    val advertisesWifi: Boolean get() = this != Usb

    companion object {
        fun fromRaw(raw: String?): ConnectionMode =
            entries.firstOrNull { it.raw == raw } ?: Both
    }
}

/**
 * Small persisted app-level toggles read by `MainActivity`/`SettingsScreen`.
 * Shares the prefs file with `DiscoveryAdvertiser`'s device-name override
 * (different keys) — mirrors the `@AppStorage` toggles in
 * `iOS/OpenSidecarPhoneApp.swift`'s `SettingsView`.
 */
object AppSettings {
    private const val PREFS_NAME = "opendisplay_settings"
    private const val KEY_SHOW_ANALYTICS = "show_analytics"
    private const val KEY_CONNECTION_MODE = "connection_mode"

    fun showAnalytics(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_ANALYTICS, false)

    fun setShowAnalytics(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOW_ANALYTICS, value).apply()
    }

    fun connectionMode(context: Context): ConnectionMode =
        ConnectionMode.fromRaw(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_CONNECTION_MODE, null),
        )

    fun setConnectionMode(context: Context, mode: ConnectionMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_CONNECTION_MODE, mode.raw).apply()
    }
}
