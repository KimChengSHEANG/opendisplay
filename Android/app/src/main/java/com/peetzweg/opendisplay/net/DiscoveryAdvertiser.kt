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
    private val serviceName: String,
    private val installId: String,
) {
    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    @Volatile private var registered = false

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
        val info =
            NsdServiceInfo().apply {
                serviceName = this@DiscoveryAdvertiser.serviceName
                serviceType = SERVICE_TYPE
                setPort(port)
                setAttribute("id", installId)
                setAttribute("pv", WireProtocol.version.toString())
            }
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    fun stop() {
        if (!registered) return
        nsdManager.unregisterService(registrationListener)
    }

    companion object {
        private const val TAG = "DiscoveryAdvertiser"

        // Trailing dot per Android's NsdServiceInfo docs (NsdManager expects
        // the fully-qualified DNS-SD type); the Mac/iOS sides register their
        // NWBrowser/NWListener type without one — same wire type either way.
        const val SERVICE_TYPE = "_opensidecar._tcp."

        /**
         * User-visible device name for the advertised service, e.g. "Pixel 7" —
         * the name set in Settings > About/Bluetooth, not the app-level
         * "Android"/"Chromebook" kind from [com.peetzweg.opendisplay.session.ReceiverSession.deviceKind].
         * Mirrors `UIDevice.current.name` on iOS.
         */
        fun deviceName(context: Context): String {
            val settingsName = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            if (!settingsName.isNullOrBlank()) return settingsName
            return Build.MODEL ?: "OpenDisplay"
        }
    }
}
