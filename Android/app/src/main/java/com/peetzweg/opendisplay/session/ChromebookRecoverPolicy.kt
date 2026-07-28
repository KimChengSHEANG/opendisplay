package com.peetzweg.opendisplay.session

/**
 * When Chromebook recover / green-screen detection may tear the live TCP
 * session (forcePeerReconnect). Mid-session tears remount ARC VDA and flash
 * black/green while the Mac redials — the "random reconnect after awhile"
 * symptom.
 *
 * Allow a tear only until the first successful paint of the current TCP
 * session (stuck first-connect green). After the panel has painted once,
 * keep the socket and recover in place (keyframes / codec rebuild).
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
