package com.peetzweg.opendisplay.session

object UdpHealthPolicy {
    private const val LOSS_FALLBACK_PCT = 20.0
    private const val BAD_WINDOWS_FOR_FALLBACK = 3

    fun shouldRequestKeyframe(lateFrames: Int, incompleteFrames: Int, decodeErrors: Int): Boolean =
        lateFrames > 0 || incompleteFrames > 0 || decodeErrors > 0

    fun shouldFallbackToTcp(lossPct: Double, consecutiveBadWindows: Int): Boolean =
        lossPct >= LOSS_FALLBACK_PCT && consecutiveBadWindows >= BAD_WINDOWS_FOR_FALLBACK

    fun incompleteRate(incompleteFrames: Int, frames: Int): Double =
        if (frames > 0) incompleteFrames.toDouble() / frames.toDouble() else 0.0

    fun qosMap(
        lossPct: Double,
        jitterMs: Double,
        incompleteRate: Double,
        lateFrames: Int,
        fecRecoveries: Int,
    ): Map<String, Any> = mapOf(
        "type" to "qos",
        "lossPct" to lossPct,
        "jitterMs" to jitterMs,
        "incompleteRate" to incompleteRate,
        "lateFrames" to lateFrames,
        "fecRecoveries" to fecRecoveries,
    )

    class FallbackTracker {
        private var bad = 0
        private var preferred = "udp"

        fun noteWindow(lossPct: Double) {
            if (shouldFallbackToTcp(lossPct, bad + 1)) {
                bad += 1
                if (bad >= BAD_WINDOWS_FOR_FALLBACK) preferred = "tcp"
            } else if (lossPct < 5.0) {
                bad = 0
            } else {
                bad += 1
            }
        }

        fun preferredVideoTransport(): String = preferred

        fun reset() {
            bad = 0
            preferred = "udp"
        }
    }
}
