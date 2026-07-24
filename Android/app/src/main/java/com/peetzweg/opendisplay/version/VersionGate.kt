package com.peetzweg.opendisplay.version

import com.peetzweg.opendisplay.wire.WireMessage
import com.peetzweg.opendisplay.wire.WireProtocol

/**
 * Peer-driven half of the update gate — mirrors `iOS/VersionGate.swift`'s
 * `applyPeer`/`PeerUpdateSignal`. No remote-config force lever yet (v1, see
 * `COMPATIBILITY.md` §5); this is the "peer signals minimum" the Mac and old
 * Macs can already drive over the wire:
 *
 * - `welcome` with `pv` below our [WireProtocol.minSupportedPeer] (or a Mac
 *   old enough to never send `welcome` at all) → the *Mac* needs updating —
 *   soft [Status.Recommended] pointing at the project site, since the fix
 *   isn't on this device.
 * - `updateRequired` → the Mac refuses this pairing until we update — hard
 *   [Status.Required], blocking.
 *
 * Pure Kotlin (no `android.*`), unit-testable like `HostSleepController`.
 */
class VersionGate {
    data class Update(val message: String, val url: String)

    sealed class Status {
        data object Ok : Status()
        data class Recommended(val update: Update) : Status()
        data class Required(val update: Update) : Status()
    }

    var status: Status = Status.Ok
        private set

    /** Feed every control message here; only `welcome`/`updateRequired` affect the gate. */
    fun onControl(map: Map<String, Any>) {
        when (map["type"]) {
            WireMessage.welcome -> {
                val macPv = (map["pv"] as? Number)?.toInt() ?: WireProtocol.assumedWhenAbsent
                status = if (macPv < WireProtocol.minSupportedPeer) {
                    Status.Recommended(Update(OLD_MAC_MESSAGE, PROJECT_SITE_URL))
                } else {
                    Status.Ok
                }
            }
            WireMessage.updateRequired -> {
                val message = map["message"] as? String ?: UPDATE_REQUIRED_FALLBACK
                val store = map["store"] as? String ?: PROJECT_SITE_URL
                status = Status.Required(Update(message, store))
            }
        }
    }

    companion object {
        const val PROJECT_SITE_URL = "https://peetzweg.github.io/opendisplay/"
        const val OLD_MAC_MESSAGE =
            "The OpenDisplay app on your Mac is too old for this device. Update OpenDisplay on your Mac to reconnect."
        const val UPDATE_REQUIRED_FALLBACK =
            "Update OpenDisplay to keep using your second display."
    }
}
