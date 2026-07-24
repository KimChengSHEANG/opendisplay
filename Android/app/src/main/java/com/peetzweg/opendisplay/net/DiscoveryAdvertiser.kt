package com.peetzweg.opendisplay.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.peetzweg.opendisplay.wire.WireProtocol

/**
 * Advertises this receiver over NSD (mDNS/Bonjour) so the Mac's WiFi picker
 * finds it — mirrors `PhoneReceiver.advertisedService` on iOS and matches
 * what the Mac browses for: `NWBrowser(for: .bonjourWithTXTRecord(type:
 * "_opensidecar._tcp", ...))`.
 */
class DiscoveryAdvertiser(
    private val context: Context,
    private var serviceName: String,
    private val installId: String,
    private val deviceKind: String = "Android",
) {
    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    @Volatile private var registered = false
    private var lastPort = 9000

    private val registrationListener =
        object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registered = true
                Log.i(TAG, "advertising as \"${info.serviceName}\"")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "registration failed: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                registered = false
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "unregistration failed: $errorCode")
            }
        }

    fun start(port: Int = 9000) {
        lastPort = port
        val info =
            NsdServiceInfo().apply {
                serviceName = this@DiscoveryAdvertiser.serviceName
                serviceType = SERVICE_TYPE
                setPort(port)
                setAttribute("id", installId)
                setAttribute("pv", WireProtocol.version.toString())
                setAttribute("device", deviceKind)
            }
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    fun stop() {
        if (!registered) return
        nsdManager.unregisterService(registrationListener)
    }

    /** User edited the device name in Settings — re-advertise under it. Mirrors `PhoneReceiver.setServiceName`. */
    fun updateServiceName(name: String) {
        if (name == serviceName) return
        serviceName = name
        if (registered) {
            stop()
        }
        start(lastPort)
    }

    companion object {
        private const val TAG = "DiscoveryAdvertiser"

        // Trailing dot per Android's NsdServiceInfo docs (NsdManager expects
        // the fully-qualified DNS-SD type); the Mac/iOS sides register their
        // NWBrowser/NWListener type without one — same wire type either way.
        const val SERVICE_TYPE = "_opensidecar._tcp."

        private const val PREFS_NAME = "opendisplay_settings"
        private const val KEY_DEVICE_NAME = "device_name"

        /**
         * User-visible device name for the advertised service, e.g. "Pixel 7" —
         * a user override persisted via [setSavedName] takes priority (mirrors
         * `deviceName`/`DeviceNameField` in `iOS/OpenSidecarPhoneApp.swift`),
         * then the name set in Settings > About/Bluetooth, not the app-level
         * "Android"/"Chromebook" kind from [com.peetzweg.opendisplay.session.ReceiverSession.deviceKind].
         */
        fun deviceName(context: Context): String {
            val saved = savedName(context)
            if (!saved.isNullOrBlank()) return saved
            val settingsName = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            if (!settingsName.isNullOrBlank()) return settingsName
            return Build.MODEL ?: "OpenDisplay"
        }

        /** The persisted override, or null if the user never set one. */
        fun savedName(context: Context): String? =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_DEVICE_NAME, null)

        /** Persists the user's device-name override for [deviceName]/[SettingsScreen]. Blank clears it. */
        fun setSavedName(context: Context, name: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val trimmed = name.trim()
            if (trimmed.isEmpty()) prefs.edit().remove(KEY_DEVICE_NAME).apply()
            else prefs.edit().putString(KEY_DEVICE_NAME, trimmed).apply()
        }
    }
}
