package com.peetzweg.opendisplay.session

import android.app.Activity
import android.graphics.Point
import android.os.Build
import android.view.WindowManager

/**
 * Physical panel size for the Mac `hello` handshake.
 *
 * [android.util.DisplayMetrics.widthPixels] is the *app* window (often
 * shorter on ChromeOS by the shelf/caption). Prefer the real display size
 * so Standard / More Space / Extra Space map against the full panel.
 */
object PanelMetrics {
    data class Size(val wide: Int, val high: Int, val density: Double)

    fun of(activity: Activity): Size {
        val density = activity.resources.displayMetrics.density.toDouble()
        val (w, h) = realPixels(activity.windowManager)
        return Size(w.coerceAtLeast(2), h.coerceAtLeast(2), density)
    }

    private fun realPixels(wm: WindowManager): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            if (bounds.width() > 0 && bounds.height() > 0) {
                return bounds.width() to bounds.height()
            }
        }
        val point = Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(point)
        return point.x to point.y
    }
}
