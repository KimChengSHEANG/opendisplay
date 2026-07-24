package com.peetzweg.opendisplay.session

import android.content.Context
import java.util.UUID

/**
 * Stable per-install identity, persisted in SharedPreferences, sent in every
 * hello. Lets the Mac recognize "same device" across reconnects — mirrors
 * `PhoneReceiver.installId` on iOS.
 */
object InstallId {
    private const val PREFS_NAME = "opendisplay_install"
    private const val KEY_ID = "install_id"

    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_ID, null)
        if (existing != null) return existing
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_ID, generated).apply()
        return generated
    }
}
