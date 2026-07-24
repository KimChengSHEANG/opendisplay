package com.peetzweg.opendisplay.ui

import android.graphics.Bitmap
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Local Mac cursor overlay that bypasses Compose.
 *
 * iOS updates a `CALayer` directly (no implicit animations). We do the same
 * with an [ImageView]: size/sprite via layout params only when they change,
 * position via `translationX`/`translationY`.
 *
 * Chromebook mouse: hover→Mac→echo is a full RTT and feels laggy. While the
 * local pointer is moving we paint immediately ([moveLocal]) and ignore Mac
 * echo positions for a short window so the overlay stays glued to the finger
 * / trackpad (sprite still comes from Mac `cursorImg`).
 */
class CursorController(private val chromebook: Boolean = false) {
    @Volatile private var host: View? = null
    @Volatile private var view: ImageView? = null

    private var bitmap: Bitmap? = null
    private var normW: Float = 0f
    private var normH: Float = 0f
    private var anchorX: Float = 0f
    private var anchorY: Float = 0f
    private var x: Float = 0.5f
    private var y: Float = 0.5f
    private var visible: Boolean = false
    private var laidOutW: Int = -1
    private var laidOutH: Int = -1
    /** Uptime deadline: prefer local hover/touch position over Mac echo. */
    @Volatile private var localDriveUntilMs: Long = 0L

    fun attach(host: View, cursorView: ImageView) {
        this.host = host
        this.view = cursorView
        // Hardware layer can break SurfaceView hole-punch compositing on
        // ChromeOS ARC (black stream). Phones use TextureView — HW layer OK.
        if (!chromebook) {
            cursorView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }
        runOnMain { applyAll() }
    }

    fun detach() {
        host = null
        view = null
        laidOutW = -1
        laidOutH = -1
        localDriveUntilMs = 0L
    }

    /**
     * Immediate local position from Chromebook hover/touch (UI thread).
     * Does not wait for the Mac echo — mirrors a native OS pointer.
     */
    fun moveLocal(x: Float, y: Float) {
        this.x = x
        this.y = y
        this.visible = true
        localDriveUntilMs = SystemClock.uptimeMillis() + LOCAL_DRIVE_MS
        val v = view ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyPosition(v)
        } else {
            val posted = v.handler?.postAtFrontOfQueue { applyPosition(v) } == true
            if (!posted) v.post { applyPosition(v) }
        }
    }

    fun move(x: Float, y: Float, visible: Boolean) {
        // While local pointer is driving, Mac echo is one RTT stale — skip
        // position so we don't tug the cursor backward every 8ms.
        if (visible && SystemClock.uptimeMillis() < localDriveUntilMs) return
        this.x = x
        this.y = y
        this.visible = visible
        val v = view ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyPosition(v)
        } else {
            val posted = v.handler?.postAtFrontOfQueue { applyPosition(v) } == true
            if (!posted) v.post { applyPosition(v) }
        }
    }

    fun setSprite(
        bitmap: Bitmap,
        normW: Float,
        normH: Float,
        anchorX: Float,
        anchorY: Float,
    ) {
        this.bitmap = bitmap
        this.normW = normW
        this.normH = normH
        this.anchorX = anchorX
        this.anchorY = anchorY
        laidOutW = -1
        laidOutH = -1
        runOnMain { applyAll() }
    }

    fun hide() {
        visible = false
        localDriveUntilMs = 0L
        runOnMain {
            view?.visibility = View.GONE
        }
    }

    /** Hover left the surface — let Mac echo resume after a short grace. */
    fun endLocalDrive() {
        localDriveUntilMs = SystemClock.uptimeMillis() + LOCAL_HANDOFF_MS
    }

    /** Host size changed (rotation / window resize) — recompute pixel size + translation. */
    fun relayout() {
        laidOutW = -1
        laidOutH = -1
        runOnMain { applyAll() }
    }

    private fun applyAll() {
        val v = view ?: return
        val bmp = bitmap
        if (bmp != null &&
            (v.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap !== bmp
        ) {
            v.setImageBitmap(bmp)
        }
        applyPosition(v)
    }

    private fun applyPosition(v: ImageView) {
        val host = host ?: return
        val pw = host.width
        val ph = host.height
        if (pw <= 0 || ph <= 0) return
        val bmp = bitmap
        // Local hover can show before the first sprite arrives — use a
        // density-sized placeholder arrow box so motion still feels native.
        val show = visible && (bmp != null || chromebook) &&
            ((normW > 0f && normH > 0f) || chromebook)
        if (!show) {
            v.visibility = View.GONE
            return
        }
        var w: Int
        var h: Int
        if (normW > 0f && normH > 0f) {
            w = (normW * pw).toInt().coerceAtLeast(1)
            h = (normH * ph).toInt().coerceAtLeast(1)
        } else {
            val d = host.resources.displayMetrics.density
            w = (24f * d).toInt().coerceAtLeast(24)
            h = (24f * d).toInt().coerceAtLeast(24)
        }
        if (chromebook && normW > 0f) {
            val density = host.resources.displayMetrics.density
            val minLong = (32f * density).toInt().coerceAtLeast(48)
            val boost = 1.6f
            w = (w * boost).toInt().coerceAtLeast(1)
            h = (h * boost).toInt().coerceAtLeast(1)
            if (maxOf(w, h) < minLong) {
                val s = minLong.toFloat() / maxOf(w, h).toFloat()
                w = (w * s).toInt().coerceAtLeast(1)
                h = (h * s).toInt().coerceAtLeast(1)
            }
        }
        if (w != laidOutW || h != laidOutH) {
            val lp = (v.layoutParams as FrameLayout.LayoutParams).apply {
                width = w
                height = h
                leftMargin = 0
                topMargin = 0
                gravity = Gravity.TOP or Gravity.START
            }
            v.layoutParams = lp
            laidOutW = w
            laidOutH = h
        }
        v.translationX = x * pw - anchorX * w
        v.translationY = y * ph - anchorY * h
        if (v.visibility != View.VISIBLE) v.visibility = View.VISIBLE
    }

    private fun runOnMain(block: () -> Unit) {
        val v = view
        if (v == null) {
            if (Looper.myLooper() == Looper.getMainLooper()) block()
            return
        }
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else v.post(block)
    }

    companion object {
        /** Keep local pointer authority long enough to cover one WiFi RTT. */
        private const val LOCAL_DRIVE_MS = 120L
        /** After hover exit, brief grace before Mac echo can tug position. */
        private const val LOCAL_HANDOFF_MS = 40L
    }
}
