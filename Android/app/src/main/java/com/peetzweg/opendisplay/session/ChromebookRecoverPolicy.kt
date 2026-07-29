package com.peetzweg.opendisplay.session

/**
 * When Chromebook recover / green-screen detection may tear the live TCP
 * session (forcePeerReconnect). Mid-session tears remount ARC VDA and flash
 * black/green while the Mac redials — the "random reconnect after awhile"
 * symptom.
 *
 * Allow a tear only until the first successful paint of the current TCP
 * session (stuck first-connect green via no-paint recover). After the panel
 * has painted once, keep the socket and recover in place (keyframes / codec
 * rebuild).
 *
 * PixelCopy "solid green" during VDA warm-up is normal (uninitialized YUV) —
 * never treat it as failure before the first paint, and never tear TCP from
 * green samples after paint (that remount flash is the brief glitch users see).
 */
object ChromebookRecoverPolicy {
    enum class Action { RequestKeyframe, RebuildCodec, TearSession }

    /**
     * @param hasPaintedThisConnection true once the decoder has rendered at
     *   least one frame for this TCP session
     */
    fun allowForceReconnect(hasPaintedThisConnection: Boolean): Boolean =
        !hasPaintedThisConnection

    /**
     * Whether a PixelCopy solid-green sample should trigger recover at all.
     * False during VDA allocate / before first paint (warm-up looks green).
     * True only after at least one decoded frame has been presented.
     */
    fun shouldActOnGreenSample(hasPaintedThisConnection: Boolean): Boolean =
        hasPaintedThisConnection

    /**
     * Green after a real paint: ask for an IDR in place. Never tear TCP from
     * PixelCopy green — that remounts ARC VDA and flashes black/green.
     */
    fun onGreenScreen(): Action = Action.RequestKeyframe

    /**
     * Decode-error recovery — UDP never tears the control TCP socket.
     * After several VDA failures, rebuild the codec in place; otherwise ask
     * for an IDR.
     */
    fun onDecodeError(transport: String, consecutiveErrors: Int): Action {
        if (transport == "udp") {
            return if (consecutiveErrors >= 5) Action.RebuildCodec else Action.RequestKeyframe
        }
        return existingTcpBehavior(consecutiveErrors)
    }

    /** Phone / TCP video: escalate to session tear only after many failures. */
    private fun existingTcpBehavior(consecutiveErrors: Int): Action = when {
        consecutiveErrors >= 10 -> Action.TearSession
        consecutiveErrors >= 5 -> Action.RebuildCodec
        else -> Action.RequestKeyframe
    }
}
