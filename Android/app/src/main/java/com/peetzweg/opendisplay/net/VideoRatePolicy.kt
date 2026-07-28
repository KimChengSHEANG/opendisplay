package com.peetzweg.opendisplay.net

data class RateAction(
    val bitrate: Int?,
    val forceKeyframe: Boolean,
    val preferTcpNextSession: Boolean,
)

/** AIMD mirror of Mac `VideoRateController` — incompleteRate replaces nackRate. */
class VideoRatePolicy(initialBitrate: Int) {
    private var bitrate: Int = maxOf(2_000_000, initialBitrate)

    fun next(lossPct: Double, jitterMs: Double, incompleteRate: Double): RateAction {
        if (lossPct >= 20.0) {
            return RateAction(bitrate = null, forceKeyframe = true, preferTcpNextSession = true)
        }
        if (lossPct > 8.0 || jitterMs > 30.0) {
            bitrate = maxOf(4_000_000, bitrate - 2_000_000)
            return RateAction(bitrate = bitrate, forceKeyframe = false, preferTcpNextSession = false)
        }
        if (incompleteRate > 0.15) {
            return RateAction(bitrate = null, forceKeyframe = true, preferTcpNextSession = false)
        }
        if (lossPct < 2.0 && jitterMs < 15.0) {
            bitrate = minOf(bitrate + 2_000_000, 40_000_000)
            return RateAction(bitrate = bitrate, forceKeyframe = false, preferTcpNextSession = false)
        }
        return RateAction(bitrate = null, forceKeyframe = false, preferTcpNextSession = false)
    }

    companion object {
        const val UDP_INITIAL_BITRATE = 24_000_000
    }
}
