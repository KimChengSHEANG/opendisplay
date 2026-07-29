package com.peetzweg.opendisplay.session

/**
 * Chromebook mounts the streaming SurfaceView after TCP connect, so the opening
 * IDR often lands before decode surface exists. Mac schedules a follow-up IDR
 * after capture starts using this delay.
 */
object FirstFramePolicy {
    fun followUpIdrDelayMs(deviceKind: String?): Long? =
        if (deviceKind == "Chromebook") 250L else null
}
