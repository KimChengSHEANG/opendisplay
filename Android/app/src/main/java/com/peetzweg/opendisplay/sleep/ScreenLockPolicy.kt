package com.peetzweg.opendisplay.sleep

/**
 * Pure rules for Android screen lock broadcasts.
 * Chromebooks often have no keyguard (`isDeviceSecure == false`); gating stop
 * on lock previously left half-dead sessions that looked frozen after idle.
 */
object ScreenLockPolicy {
    /** Always park the session when the panel goes dark. */
    fun shouldStopOnScreenOff(): Boolean = true

    /**
     * Resume listen on SCREEN_ON only when there is no secure keyguard.
     * Secure phones wait for [android.content.Intent.ACTION_USER_PRESENT].
     */
    fun shouldResumeOnScreenOn(isDeviceSecure: Boolean): Boolean = !isDeviceSecure
}
