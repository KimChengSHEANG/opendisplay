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
    /**
     * @param hasPaintedThisConnection true once the decoder has rendered at
     *   least one frame for this TCP session
     */
    fun allowForceReconnect(hasPaintedThisConnection: Boolean): Boolean =
        !hasPaintedThisConnection
}
