package com.peetzweg.opendisplay.settings

import android.content.Context

/**
 * Small persisted app-level toggles read by `MainActivity`/`SettingsScreen`.
 * Shares the prefs file with `DiscoveryAdvertiser`'s device-name override
 * (different keys) — mirrors the `@AppStorage` toggles in
 * `iOS/OpenSidecarPhoneApp.swift`'s `SettingsView`.
 */
object AppSettings {
    private const val PREFS_NAME = "opendisplay_settings"
    private const val KEY_SHOW_ANALYTICS = "show_analytics"

    fun showAnalytics(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_SHOW_ANALYTICS, false)

    fun setShowAnalytics(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOW_ANALYTICS, value).apply()
    }
}
