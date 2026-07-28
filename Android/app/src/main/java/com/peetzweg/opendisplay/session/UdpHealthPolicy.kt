package com.peetzweg.opendisplay.session

object UdpHealthPolicy {
    private const val LOSS_FALLBACK_PCT = 20.0
    private const val BAD_WINDOWS_FOR_FALLBACK = 3

    fun shouldRequestKeyframe(lateFrames: Int, incompleteFrames: Int, decodeErrors: Int): Boolean =
        lateFrames > 0 || incompleteFrames > 0 || decodeErrors > 0

    fun shouldFallbackToTcp(lossPct: Double, consecutiveBadWindows: Int): Boolean =
        lossPct >= LOSS_FALLBACK_PCT && consecutiveBadWindows >= BAD_WINDOWS_FOR_FALLBACK

    fun qosMap(
        lossPct: Double,
        jitterMs: Double,
        nackRate: Double,
        lateFrames: Int,
        fecRecoveries: Int,
    ): Map<String, Any> = mapOf(
        "type" to "qos",
        "lossPct" to lossPct,
        "jitterMs" to jitterMs,
        "nackRate" to nackRate,
        "lateFrames" to lateFrames,
        "fecRecoveries" to fecRecoveries,
    )
}
