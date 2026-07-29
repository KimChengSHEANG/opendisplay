package com.peetzweg.opendisplay.session

/**
 * Mid-session video stall recovery for Chromebook.
 *
 * TCP silence watchdog only watches *any* bytes (including liveness pings).
 * After the first paint, [VideoDecoder.hasRendered] stays true forever, and
 * PixelCopy green watching used to stop after ~12s — so a later VDA/UDP wedge
 * (frozen last frame, dead clicks feeling) never recovered.
 *
 * Never tear TCP from a stall (that remount flash is worse); escalate kf →
 * in-place codec rebuild.
 */
object VideoStallPolicy {
    /** Ask for an IDR once video has been quiet this long. */
    const val KEYFRAME_AFTER_MS = 2_000L
    /** Rebuild ARC VDA in place if still quiet after keyframe asks. */
    const val REBUILD_AFTER_MS = 5_000L

    enum class Action { None, RequestKeyframe, RebuildCodec }

    /**
     * @param ageMs ms since last video AU reached the session; null if none yet
     * @param hasPaintedThisConnection true after the first successful present
     */
    fun action(
        ageMs: Long?,
        hasPaintedThisConnection: Boolean,
        connected: Boolean,
    ): Action {
        if (!connected || !hasPaintedThisConnection || ageMs == null) return Action.None
        if (ageMs >= REBUILD_AFTER_MS) return Action.RebuildCodec
        if (ageMs >= KEYFRAME_AFTER_MS) return Action.RequestKeyframe
        return Action.None
    }
}
